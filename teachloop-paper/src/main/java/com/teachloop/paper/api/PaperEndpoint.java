package com.teachloop.paper.api;

import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.paper.domain.*;
import com.teachloop.paper.importing.AnswerTableParser;
import com.teachloop.paper.importing.DocumentTextExtractor;
import com.teachloop.paper.importing.ImportException;
import com.teachloop.paper.importing.PaperHeaderMeta;
import com.teachloop.paper.importing.PaperTextParser;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiError;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/papers")
public class PaperEndpoint {

    private final PaperRepository papers;
    private final PaperSectionRepository sections;
    private final ObjectiveItemRepository items;
    private final SectionCriteriaRepository criteria;
    private final PaperContentSnapshotRepository snapshots;
    private final Clock clock;
    private final Ports.PaperUsage usage;
    private final Ports.ObjectiveRecompute recompute;

    private final AuditPort audit;

    /** 消息 key → 文案（D-34 起的错误一律走 key）。单测里可以传 null，此时直接返回 key。 */
    private final MessageSource messages;

    /** 快照 payload 的序列化器：只写我们自己定义的小 record，不涉及实体代理。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 目标总分比较容差（AC-4.26②）：0.001，避免 148.5 vs 148.50 这种小数位差异误拦。 */
    private static final BigDecimal TOTAL_TOLERANCE = new BigDecimal("0.001");

    /** 单部分文档替换重建后新建的部分标题；与解析器「没写部分标题」的兜底同名。 */
    private static final String DEFAULT_SECTION_TITLE = "综合";

    /** 每卷保留的快照条数（AC-4.24⑥）。 */
    private static final int SNAPSHOT_KEEP = 3;

    /** D-28：作文默认内容占比；判分侧读同一配置，这里只用于向导回显当前比例。 */
    @Value("${teachloop.grading.essay-default-rubric.content-percent:80}")
    private int essayContentPercent = 80;

    /** 旧测试/内部构造兼容：没有布置统计时按 0 处理。 */
    public PaperEndpoint(PaperRepository papers, PaperSectionRepository sections,
                         ObjectiveItemRepository items, SectionCriteriaRepository criteria,
                         AuditPort audit, Clock clock) {
        this(papers, sections, items, criteria, audit, clock, id -> 0, (paperId, keys) -> 0, null, null);
    }

    /** 兼容旧调用：没有快照仓储与文案资源（replace=true 需要前者，发消息给前端需要后者）。 */
    public PaperEndpoint(PaperRepository papers, PaperSectionRepository sections,
                         ObjectiveItemRepository items, SectionCriteriaRepository criteria,
                         AuditPort audit, Clock clock, Ports.PaperUsage usage,
                         Ports.ObjectiveRecompute recompute) {
        this(papers, sections, items, criteria, audit, clock, usage, recompute, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaperEndpoint(PaperRepository papers, PaperSectionRepository sections,
                         ObjectiveItemRepository items, SectionCriteriaRepository criteria,
                         AuditPort audit, Clock clock, Ports.PaperUsage usage,
                         Ports.ObjectiveRecompute recompute, PaperContentSnapshotRepository snapshots,
                         MessageSource messages) {
        this.papers = papers;
        this.sections = sections;
        this.items = items;
        this.criteria = criteria;
        this.audit = audit;
        this.snapshots = snapshots;
        this.messages = messages;
        this.clock = clock;
        this.usage = usage;
        this.recompute = recompute;
    }

    /**
     * 新建试卷（D-34：基本信息里多了目标总分 / 命题人 / 审题人）。
     *
     * <p>三个字段都可空；{@code authorName} 空则写当前登录教师的显示名（AC-4.27②）。
     * {@code targetTotalPoints} 只做发布闸门，不参与 {@code totalPoints} 的计算。</p>
     */
    public record CreatePaper(@NotBlank String title, Integer defaultDurationMinutes,
                              @Size(max = 80) String authorName, @Size(max = 80) String reviewerName,
                              BigDecimal targetTotalPoints) {
    }

    /**
     * {@code PUT /api/papers/{id}/meta} 的请求体（D-34 / AC-4.26④、AC-4.27③）。
     *
     * <p><b>全量替换</b>：字段为 null 即清空，不是「不改」。只改这三列，
     * 不动 title / duration，也不重算 {@code total_points}。</p>
     */
    public record UpdateMeta(@Size(max = 80) String authorName, @Size(max = 80) String reviewerName,
                             BigDecimal targetTotalPoints) {
    }

    public record CreateSection(@NotBlank String title, BigDecimal pointsPerItem) {
    }
    public record UpdateSection(String title, BigDecimal pointsPerItem) {
    }

    public record CreateItem(@NotNull Integer number, @NotNull QuestionType questionType, String stem,
                             String options, String answerKey, @NotNull BigDecimal points, UUID sectionId,
                             String scoringStrategy, String rubricMarkdown, String referenceMarkdown) {
    }

    public record SetQuestionType(@NotNull QuestionType questionType) {
    }

    public record BatchQuestionType(@NotNull Integer fromNumber, @NotNull Integer toNumber,
                                    @NotNull QuestionType questionType) {
    }

    public record BatchResult(int updated, String questionType) {
    }

    public record SetScoringStrategy(@NotBlank String scoringStrategy) {
    }

    public record CreateCriteria(@NotBlank String name, @NotNull BigDecimal maxPoints, String description,
                                 UUID sectionId) {
    }

    public record Rubric(String rubricMarkdown, String gradingPromptTemplate) {
    }

    /**
     * 列表与详情共用的试卷视图。字段顺序即 JSON 顺序，前端按此契约消费：
     * {@code id,title,totalPoints,version,rubricVersion,status,defaultDurationMinutes,assignmentCount,
     * authorName,reviewerName,targetTotalPoints}。
     *
     * <p>列表接口不带正文（{@code contentMarkdown} 只在详情里），避免 {@code /api/papers} 变重。</p>
     */
    public record PaperView(String id, String title, BigDecimal totalPoints, int version, int rubricVersion,
                            String status, Integer defaultDurationMinutes, int assignmentCount,
                            String authorName, String reviewerName, BigDecimal targetTotalPoints) {
    }

    public record ItemView(String id, int number, String questionType, String stem, String options,
                           String answerKey, BigDecimal points, String scoringStrategy,
                           String rubricMarkdown, String referenceMarkdown) {
    }

    public record SectionView(String id, String title, int position, BigDecimal pointsPerItem, List<ItemView> items) {
    }

    /** 详情：比 {@link PaperView} 多一份原文（{@code contentMarkdown}），用于回填编辑器。 */
    public record PaperDetail(PaperView paper, List<SectionView> sections, List<String> criteriaNames,
                              String rubricMarkdown, String gradingPromptTemplate, int essayContentPercent,
                              String contentMarkdown) {
    }

    public record ImportedItem(int number, String stem, String options, String answerKey,
                               BigDecimal points, String questionType) {
    }

    /** 导入后每个部分落的题数，前端用它展示「文档结构被保留成了什么」。 */
    public record ImportSection(String title, int itemCount, BigDecimal pointsPerItem) {
    }

    public record UpdateItem(String stem, String options, String answerKey, BigDecimal points,
                             String rubricMarkdown, String referenceMarkdown) {
    }

    public record AnswerTableRequest(@jakarta.validation.constraints.NotBlank String text) {
    }

    public record AnswerTableResult(int updated, List<Integer> unknownNumbers, List<String> warnings) {
    }

    public record ImportTextRequest(String text, UUID sectionId) {
    }

    /**
     * 「生成考题」的结果（D-34 / FR-149）。
     *
     * <p>{@code contentMarkdown} 是本次提交的原文（前端回填编辑器、判定能不能再次生成）；
     * {@code metaHints} 是从原文头部读到的命题人 / 审题人 / 总分，前端**只回填空着的字段**。</p>
     */
    public record ImportResult(int imported, List<Integer> missingAnswers, List<ImportedItem> items,
                               List<ImportSection> sections, List<String> warnings,
                               String contentMarkdown, PaperHeaderMeta.Hints metaHints) {
    }

    /** 内部用的落库结果，外层再包 {@link ImportResult}（原文与提示只有端点知道）。 */
    private record ImportRows(int imported, List<Integer> missingAnswers, List<ImportedItem> items,
                              List<ImportSection> sections, List<String> warnings) {
    }

    /** 快照 payload 的四个小 record：只序列化我们关心的列，不把实体直接丢给 Jackson。 */
    private record SnapshotSection(String id, String title, int position, BigDecimal pointsPerItem) {
    }

    private record SnapshotItem(String id, int number, int position, String questionType, String stem,
                                String options, String answerKey, BigDecimal points, String scoringStrategy,
                                String rubricMarkdown, String referenceMarkdown, String sectionId) {
    }

    private record SnapshotCriteria(String id, String name, BigDecimal maxPoints, String description,
                                    int position, String sectionId) {
    }

    private record SnapshotPayload(List<SnapshotSection> sections, List<SnapshotItem> items,
                                   List<SnapshotCriteria> criteria) {
    }

    @PostMapping
    @Transactional
    public PaperView create(@RequestBody @Valid CreatePaper body, @RequestAttribute("currentUser") CurrentUser me) {
        var p = new Paper(UUID.randomUUID(), body.title().trim(), body.defaultDurationMinutes(), me.id(), Instant.now(clock));
        // AC-4.27②：命题人默认当前登录教师的显示名；显式传了就用传的（自由文本，不校验账号）。
        p.setAuthorName(body.authorName() == null || body.authorName().isBlank()
                ? me.displayName() : body.authorName().trim());
        p.setReviewerName(trimToNull(body.reviewerName()));
        if (body.targetTotalPoints() != null) {
            p.setTargetTotalPoints(requireNonNegativeTarget(body.targetTotalPoints()));
        }
        papers.save(p);
        return view(p);
    }

    @GetMapping
    public List<PaperView> mine(@RequestAttribute("currentUser") CurrentUser me) {
        return papers.findByAuthorIdOrderByCreatedAtDesc(me.id()).stream().map(this::view).toList();
    }

    /**
     * D-34 / AC-4.26④、AC-4.27③：改目标总分与命题人 / 审题人。
     *
     * <p>三个字段**全量替换**（null 即清空）；不动 title / duration，也不重算 {@code total_points}
     * （目标总分只是发布闸门）。已发布卷也允许改，写审计 {@code paper:meta_updated}。</p>
     */
    @PutMapping("/{id}/meta")
    @Transactional
    public PaperView updateMeta(@PathVariable UUID id, @RequestBody @Valid UpdateMeta body,
                                @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = load(id);
        requireAuthor(p, me);
        p.setAuthorName(trimToNull(body.authorName()));
        p.setReviewerName(trimToNull(body.reviewerName()));
        p.setTargetTotalPoints(body.targetTotalPoints() == null
                ? null : requireNonNegativeTarget(body.targetTotalPoints()));
        papers.save(p);
        audit.record(new AuditEvent(me.id(), "paper:meta_updated", "paper", id.toString(),
                Map.of("status", p.getStatus().name())));
        return view(p);
    }

    /** T206：复制为新卷（互不影响，新卷为 DRAFT）。 */
    @PostMapping("/{id}/copy")
    @Transactional
    public PaperView copy(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        Paper source = load(id);
        Paper copy = papers.save(new Paper(UUID.randomUUID(), source.getTitle() + "（副本）",
                source.getDefaultDurationMinutes(), me.id(), Instant.now(clock)));
        copy.setRubricMarkdown(source.getRubricMarkdown());
        copy.setGradingPromptTemplate(source.getGradingPromptTemplate());
        copy.setTotalPoints(source.getTotalPoints());
        // D-34：原文与目标总分跟着复制（否则复制出来的卷子没法再生成、也丢了发布闸门）；
        // 命题人 = 当前教师，审题人不继承（新卷的审题人要自己填）。
        copy.setContentMarkdown(source.getContentMarkdown());
        copy.setTargetTotalPoints(source.getTargetTotalPoints());
        copy.setAuthorName(me.displayName());
        copy.setReviewerName(null);
        papers.save(copy);
        java.util.Map<UUID, UUID> sectionMap = new java.util.HashMap<>();
        for (PaperSection section : sections.findByPaperIdOrderByPosition(id)) {
            UUID newId = UUID.randomUUID();
            sectionMap.put(section.getId(), newId);
            sections.save(new PaperSection(newId, copy.getId(), section.getTitle(),
                    section.getPosition(), section.getPointsPerItem()));
        }
        for (ObjectiveItem item : items.findByPaperIdOrderByNumber(id)) {
            ObjectiveItem clone = new ObjectiveItem(UUID.randomUUID(), copy.getId(),
                    sectionMap.getOrDefault(item.getPaperSectionId(), item.getPaperSectionId()),
                    item.getNumber(), item.getPosition(), item.getQuestionType(), item.getStem(),
                    item.getOptions(), item.getAnswerKey(), item.getPoints());
            clone.setScoringStrategy(item.getScoringStrategy());
            items.save(clone);
        }
        for (SectionCriteria c : criteria.findByPaperIdOrderByPosition(id)) {
            criteria.save(new SectionCriteria(UUID.randomUUID(), copy.getId(),
                    sectionMap.getOrDefault(c.getPaperSectionId(), c.getPaperSectionId()),
                    c.getName(), c.getMaxPoints(), c.getDescription(), c.getPosition()));
        }
        audit.record(AuditEvent.of(me.id(), "paper:copied", "paper", copy.getId().toString()));
        return view(copy);
    }

    /** T206：归档（不改内容，仅状态）。 */
    @PostMapping("/{id}/archive")
    @Transactional
    public PaperView archive(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = load(id);
        p.archive();
        audit.record(AuditEvent.of(me.id(), "paper:archived", "paper", id.toString()));
        return view(p);
    }

    @GetMapping("/{id}")
    public PaperDetail detail(@PathVariable UUID id) {
        Paper p = load(id);
        List<SectionView> secs = sections.findByPaperIdOrderByPosition(id).stream()
                .map(s -> new SectionView(s.getId().toString(), s.getTitle(), s.getPosition(),
                        s.getPointsPerItem() == null ? BigDecimal.ZERO : s.getPointsPerItem(),
                        items.findByPaperSectionIdOrderByPosition(s.getId()).stream()
                                .map(i -> itemView(i))
                                .toList()))
                .toList();
        List<String> crit = criteria.findByPaperIdOrderByPosition(id).stream().map(SectionCriteria::getName).toList();
        return new PaperDetail(view(p), secs, crit, p.getRubricMarkdown(), p.getGradingPromptTemplate(),
                essayContentPercent, p.getContentMarkdown());
    }

    @PostMapping("/{id}/sections")
    @Transactional
    public SectionView addSection(@PathVariable UUID id, @RequestBody @Valid CreateSection body,
                                  @RequestParam(defaultValue = "false") boolean confirm,
                                  @RequestAttribute("currentUser") CurrentUser me) {
        editable(id, confirm, me, "add_section");
        // 位置取「现有最大值 + 1」：删过中间部分后 size+1 会和已有位置撞车。
        int pos = sections.findByPaperIdOrderByPosition(id).stream()
                .mapToInt(PaperSection::getPosition).max().orElse(0) + 1;
        var s = new PaperSection(UUID.randomUUID(), id, body.title().trim(), pos, body.pointsPerItem());
        sections.save(s);
        return new SectionView(s.getId().toString(), s.getTitle(), pos,
                body.pointsPerItem() == null ? BigDecimal.ZERO : body.pointsPerItem(), List.of());
    }

    /**
     * 改部分的标题与「每题分值」。每题分值只作为之后新增题项的默认值，
     * 不回溯改已有题项的分值（已出分的卷子不能被静默改分）。
     */
    @PutMapping("/{id}/sections/{sectionId}")
    @Transactional
    public SectionView updateSection(@PathVariable UUID id, @PathVariable UUID sectionId,
                                     @RequestBody @Valid UpdateSection body,
                                     @RequestParam(defaultValue = "false") boolean confirm,
                                     @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = editable(id, confirm, me, "update_section");
        requireAuthor(p, me);
        PaperSection section = requireSection(id, sectionId);
        if (body.title() != null && !body.title().isBlank()) {
            section.setTitle(body.title().trim());
        }
        if (body.pointsPerItem() != null) {
            if (body.pointsPerItem().signum() < 0) {
                throw ApiException.badRequest("每题分值不能为负数");
            }
            section.setPointsPerItem(body.pointsPerItem());
        }
        sections.save(section);
        audit.record(AuditEvent.of(me.id(), "paper:section_updated", "paper", id.toString()));
        return new SectionView(section.getId().toString(), section.getTitle(), section.getPosition(),
                section.getPointsPerItem() == null ? BigDecimal.ZERO : section.getPointsPerItem(),
                items.findByPaperSectionIdOrderByPosition(sectionId).stream().map(PaperEndpoint::itemView).toList());
    }

    /** 删部分：连同其题项与评分项一起删（外键 on delete cascade），并重算总分。 */
    @DeleteMapping("/{id}/sections/{sectionId}")
    @Transactional
    public ResponseEntity<Void> deleteSection(@PathVariable UUID id, @PathVariable UUID sectionId,
                                              @RequestParam(defaultValue = "false") boolean confirm,
                                              @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = editable(id, confirm, me, "delete_section");
        requireAuthor(p, me);
        PaperSection section = requireSection(id, sectionId);
        criteria.deleteAll(criteria.findByPaperSectionIdOrderByPosition(sectionId));
        items.deleteAll(items.findByPaperSectionIdOrderByPosition(sectionId));
        sections.delete(section);
        recalcTotal(p);
        audit.record(AuditEvent.of(me.id(), "paper:section_deleted", "paper", id.toString()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/items")
    @Transactional
    public ItemView addItem(@PathVariable UUID id, @RequestBody @Valid CreateItem body,
                            @RequestParam(defaultValue = "false") boolean confirm,
                            @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = editable(id, confirm, me, "add_item");
        UUID sectionId = body.sectionId();
        if (sectionId == null) {
            sectionId = sections.findByPaperIdOrderByPosition(id).stream().findFirst()
                    .orElseThrow(() -> ApiException.badRequest("请先创建部分")).getId();
        }
        int pos = items.findByPaperSectionIdOrderByPosition(sectionId).stream()
                .mapToInt(ObjectiveItem::getPosition).max().orElse(0) + 1;
        var item = new ObjectiveItem(UUID.randomUUID(), id, sectionId, body.number(), pos,
                body.questionType(), body.stem(), body.options(), body.answerKey(), body.points());
        item.setScoringStrategy(parseStrategy(body.scoringStrategy()));
        applyEssayText(item, body.rubricMarkdown(), body.referenceMarkdown());
        items.save(item);
        recalcTotal(p);
        return itemView(item);
    }

    /**
     * 从 .docx / .md / .txt / .xlsx 导入客观题项（T186 / T217 / T231）。
     *
     * <p>题目与答案写在同一个文档里也能一次导入：答案按题号自动配对，
     * 没配上的题照样创建，但题号列进 {@code missingAnswers}。</p>
     *
     * <p>只新增 {@code objective_items}：已存在的题号跳过（进 warnings，不覆盖）；
     * 不触碰已有题项。文档写了「第X部分」时按文档结构新建部分（逐题分值跟着走），
     * 没写时导入到 {@code sectionId} 指定的部分，保持原有语义。</p>
     */
    /** T056：逐题修正题干/选项/答案，教师改拆错的那一道。 */
    @PutMapping("/{id}/items/{number}")
    @Transactional
    public ItemView updateItem(@PathVariable UUID id, @PathVariable int number,
                               @RequestBody UpdateItem body,
                               @RequestParam(defaultValue = "false") boolean confirm,
                               @RequestAttribute("currentUser") CurrentUser me) {
        editable(id, confirm, me, "update_item");
        ObjectiveItem item = items.findByPaperIdAndNumber(id, number)
                .orElseThrow(() -> ApiException.notFound("题号 " + number + " 不存在"));
        if (body.stem() != null) item.setStem(body.stem());
        if (body.options() != null) item.setOptions(body.options());
        if (body.answerKey() != null) item.setAnswerKey(body.answerKey().trim());
        // D-24 / D-25：题项级 rubric 与参考范文只对写作题开放
        if (body.rubricMarkdown() != null || body.referenceMarkdown() != null) {
            applyEssayText(item, body.rubricMarkdown(), body.referenceMarkdown());
        }
        if (body.points() != null) {
            item.setPoints(body.points());
            items.save(item);
            Paper p = load(id);
            recalcTotal(p);
        }
        items.save(item);
        if (body.answerKey() != null) {
            recompute.recomputePaper(id, List.of(new Ports.AnswerKey(id, number, item.getAnswerKey())));
        }
        audit.record(AuditEvent.of(me.id(), "paper:item_updated", "paper", id.toString()));
        return itemView(item);
    }

    /** 删题项并重算总分；题号可被之后的导入/新增重新使用。 */
    @DeleteMapping("/{id}/items/{number}")
    @Transactional
    public ResponseEntity<Void> deleteItem(@PathVariable UUID id, @PathVariable int number,
                                           @RequestParam(defaultValue = "false") boolean confirm,
                                           @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = editable(id, confirm, me, "delete_item");
        requireAuthor(p, me);
        ObjectiveItem item = items.findByPaperIdAndNumber(id, number)
                .orElseThrow(() -> ApiException.notFound("题号 " + number + " 不存在"));
        items.delete(item);
        recalcTotal(p);
        audit.record(AuditEvent.of(me.id(), "paper:item_deleted", "paper", id.toString()));
        return ResponseEntity.noContent().build();
    }

    /** T055：粘贴答案表补齐已有题项的标准答案。 */
    @PostMapping("/{id}/answers")
    @Transactional
    public AnswerTableResult fillAnswers(@PathVariable UUID id, @RequestBody @jakarta.validation.Valid AnswerTableRequest body,
                                         @RequestParam(defaultValue = "false") boolean confirm,
                                         @RequestAttribute("currentUser") CurrentUser me) {
        editable(id, confirm, me, "fill_answers");
        AnswerTableParser.Result parsed;
        try {
            // 填空题的答案不是字母（challenging / to walk），按题号白名单交给文本通道
            Set<Integer> freeText = new HashSet<>();
            Set<Integer> essays = new HashSet<>();
            for (ObjectiveItem item : items.findByPaperIdOrderByNumber(id)) {
                if (item.getQuestionType() == QuestionType.FILL_BLANK) {
                    freeText.add(item.getNumber());
                }
                if (item.getQuestionType() == QuestionType.ESSAY) {
                    essays.add(item.getNumber());
                }
            }
            parsed = AnswerTableParser.parse(body.text(), freeText, essays);
        } catch (ImportException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
        List<Integer> unknown = new ArrayList<>();
        int updated = 0;
        for (AnswerTableParser.Entry entry : parsed.entries()) {
            var item = items.findByPaperIdAndNumber(id, entry.number());
            if (item.isEmpty()) {
                unknown.add(entry.number());
                continue;
            }
            item.get().setAnswerKey(entry.answer());
            items.save(item.get());
            updated++;
        }
        audit.record(AuditEvent.of(me.id(), "paper:answers_filled", "paper", id.toString()));
        if (updated > 0) {
            List<Ports.AnswerKey> keys = parsed.entries().stream()
                    .map(e -> new Ports.AnswerKey(id, e.number(), e.answer())).toList();
            recompute.recomputePaper(id, keys);
        }
        return new AnswerTableResult(updated, unknown, parsed.warnings());
    }

    /** 兼容旧测试/内部调用的重载（不注册为端点）。 */
    public ResponseEntity<?> importFile(UUID id, MultipartFile file, UUID sectionId, CurrentUser me) {
        return importFile(id, file, sectionId, false, false, me);
    }

    /**
     * 「生成考题」的文件入口（D-34，AC-4.24②）：上传文件与编辑器文本走同一个解析与落库流程。
     *
     * <p>{@code replace=true} 表示替换重建：同一事务里先写快照、再删本卷的评分项 / 题项 / 部分，
     * 然后按原文重建（AC-4.24③⑥）。已发布的卷内容锁定，**任何 confirm 都拒绝**（409），
     * 要重建请先复制为新卷（plan §20.2 状态机）。</p>
     *
     * <p>两个入口都会把本次提交的原文写进 {@code papers.content_markdown}（AC-4.24④）。</p>
     */
    @PostMapping(path = "/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<?> importFile(@PathVariable UUID id,
                                       @RequestPart("file") MultipartFile file,
                                       @RequestParam(name = "sectionId", required = false) UUID sectionId,
                                       @RequestParam(defaultValue = "false") boolean confirm,
                                       @RequestParam(defaultValue = "false") boolean replace,
                                       @RequestAttribute("currentUser") CurrentUser me) {
        if (me != null && me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能导入试卷");
        }
        Paper p = importable(id, me);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择要导入的文件");
        }

        DocumentTextExtractor.ExtractedDocument document;
        PaperTextParser.Result parsed;
        try {
            document = DocumentTextExtractor.extract(file.getOriginalFilename(), file.getBytes());
            parsed = PaperTextParser.parse(document.lines());
        } catch (ImportException ex) {
            return ResponseEntity.badRequest()
                    .body(new ApiError(ErrorCode.BAD_REQUEST.name(), "导入失败", ex.getMessage()));
        } catch (IOException ex) {
            return ResponseEntity.badRequest()
                    .body(new ApiError(ErrorCode.BAD_REQUEST.name(), "导入失败",
                            "读取上传文件失败：" + ex.getMessage()));
        }

        return ResponseEntity.ok(importInto(p, me, new ImportCommand(sectionId, parsed, replace, confirm,
                textOf(document), PaperHeaderMeta.parse(document.lines()), "import")));
    }

    /** 粘贴整卷文本导入（与文件导入同一解析器与创建逻辑）；文本入口是「生成考题」的主路径。 */
    @PostMapping("/{id}/import/text")
    @Transactional
    public ResponseEntity<?> importText(@PathVariable UUID id, @RequestBody ImportTextRequest body,
                                        @RequestParam(defaultValue = "false") boolean confirm,
                                        @RequestParam(defaultValue = "false") boolean replace,
                                        @RequestAttribute("currentUser") CurrentUser me) {
        if (me != null && me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能导入试卷");
        }
        Paper p = importable(id, me);
        if (body.text() == null || body.text().isBlank()) {
            throw ApiException.badRequest("请粘贴试卷文字");
        }
        // 与文件导入走同一套预处理：老师经常把整卷 .md 直接粘进来，不剥掉标题/加粗/表格记号，
        // 「第X节」这类分节认不出来，题号就会串（2026-10-22 真实卷踩过）。
        PaperTextParser.Result parsed;
        DocumentTextExtractor.ExtractedDocument document;
        try {
            document = DocumentTextExtractor.extract(
                    "pasted.md", body.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            parsed = PaperTextParser.parse(document.lines());
        } catch (ImportException ex) {
            return ResponseEntity.badRequest()
                    .body(new ApiError(ErrorCode.BAD_REQUEST.name(), "导入失败", ex.getMessage()));
        }
        // 文本入口存「粘贴的原文」而不是清洗后的行：老师再打开编辑器看到的就是自己粘进来的样子
        // （重新提交时 extract 会再清一遍，解析结果不变）。
        return ResponseEntity.ok(importInto(p, me, new ImportCommand(body.sectionId(), parsed, replace, confirm,
                body.text(), PaperHeaderMeta.parse(document.lines()), "import_text")));
    }

    /** 「生成考题」的一次提交：两个入口解析完各自组装，落库流程只写一份。 */
    private record ImportCommand(UUID targetSectionId, PaperTextParser.Result parsed, boolean replace,
                                 boolean confirm, String contentMarkdown, PaperHeaderMeta.Hints hints,
                                 String source) {
    }

    /**
     * 「生成考题」的公共落库流程（文件与文本两个入口共用，AC-4.24②）。
     *
     * <p>顺序与 plan §20.4③ 一致：解析（调用方做）→ replace 时写快照（同事务）→
     * 删 {@code section_criteria} → 删 {@code objective_items} → 删 {@code paper_sections}
     * → 重建 → {@code content_markdown = 原文} → {@code recalcTotal}。</p>
     *
     * <p>已发布的卷在上游 {@link #importable(UUID)} 就被拦下（409），
     * 所以这里的 {@code confirm} 不再承担「解锁已发布卷」的职责，只作为审计事实记下来
     * （AC-4.24③：已发布卷禁止生成，服务端也拒绝；plan §20.2 状态机）。</p>
     *
     * <p>重建会清空本卷所有部分，单部分文档此时没有可供「导入到选中部分」的容器，
     * 所以 replace 且只有一个部分时先建一个「综合」部分当容器（否则 {@code resolveSection}
     * 会抛「请先创建部分」）。多部分文档按文档结构新建，不受影响。</p>
     */
    private ImportResult importInto(Paper p, CurrentUser me, ImportCommand cmd) {
        UUID paperId = p.getId();
        UUID targetSectionId = cmd.targetSectionId();
        if (cmd.replace()) {
            writeSnapshot(p, cmd.source());
            criteria.deleteAll(criteria.findByPaperIdOrderByPosition(paperId));
            items.deleteAll(items.findByPaperIdOrderByNumber(paperId));
            sections.deleteAll(sections.findByPaperIdOrderByPosition(paperId));
            // 选中的部分已被删掉（前端 replace 时不传 sectionId），重建一律按原文结构走。
            targetSectionId = null;
            if (cmd.parsed().sections().size() <= 1) {
                BigDecimal hint = cmd.parsed().sections().stream().findFirst()
                        .map(PaperTextParser.ParsedSection::pointsPerItem)
                        .filter(java.util.Objects::nonNull)
                        .orElse(BigDecimal.ONE);
                sections.save(new PaperSection(UUID.randomUUID(), paperId, DEFAULT_SECTION_TITLE, 1, hint));
            }
            audit.record(new AuditEvent(me.id(), "paper:content_replaced", "paper", paperId.toString(),
                    Map.of("source", cmd.source(), "confirm", cmd.confirm())));
        }
        // replace 的「先删后建」在上面已经做完，所以这里读到的是空表：不会再有题号跳过的分支。
        ImportRows rows = createItems(paperId, targetSectionId, cmd.parsed());
        // 总分仍由题项 + 评分项算（目标总分不覆盖它，决策 021）。
        recalcTotal(p);
        p.setContentMarkdown(cmd.contentMarkdown());
        papers.save(p);
        return new ImportResult(rows.imported(), rows.missingAnswers(), rows.items(), rows.sections(),
                rows.warnings(), cmd.contentMarkdown(), cmd.hints());
    }

    /**
     * 把解析结果落成题项（T186 / T217）。
     *
     * <p>文档里识别出多个「第X部分」时，按文档结构新建部分、逐题分值跟着走，
     * 此时 {@code targetSectionId} 不再使用；只有一个部分（或没写部分）时，
     * 仍然导入到选中的目标部分，与旧行为一致。</p>
     *
     * <p>放进来的「库里已有的题号」只用于增量导入的跳过逻辑；替换重建会先清空本卷，
     * 所以这里读不到旧题项，等价于「全部重建」（AC-4.24③）。</p>
     */
    private ImportRows createItems(UUID paperId, UUID targetSectionId, PaperTextParser.Result parsed) {
        Set<Integer> existingNumbers = new HashSet<>();
        for (ObjectiveItem existing : items.findByPaperIdOrderByNumber(paperId)) {
            existingNumbers.add(existing.getNumber());
        }
        List<String> warnings = new ArrayList<>(parsed.warnings());
        List<ImportedItem> imported = new ArrayList<>();
        List<Integer> missingAnswers = new ArrayList<>();
        List<ImportSection> summary = new ArrayList<>();
        List<PaperTextParser.ParsedSection> parsedSections = parsed.sections();

        if (parsedSections.size() <= 1) {
            PaperSection target = resolveSection(paperId, targetSectionId);
            List<PaperTextParser.ParsedItem> list = parsed.items();
            fillSection(paperId, target, list, existingNumbers, warnings, imported, missingAnswers);
            summary.add(new ImportSection(target.getTitle(), list.size(), target.getPointsPerItem()));
        } else {
            if (targetSectionId != null) {
                warnings.add("文档里识别到 " + parsedSections.size() + " 个部分，已按文档结构新建部分，"
                        + "你选中的目标部分没有使用");
            }
            int position = sections.findByPaperIdOrderByPosition(paperId).stream()
                    .mapToInt(PaperSection::getPosition).max().orElse(0);
            Map<String, PaperSection> existingByTitle = new LinkedHashMap<>();
            for (PaperSection section : sections.findByPaperIdOrderByPosition(paperId)) {
                existingByTitle.putIfAbsent(section.getTitle(), section);
            }
            for (PaperTextParser.ParsedSection parsedSection : parsedSections) {
                String title = parsedSection.title().isBlank() ? DEFAULT_SECTION_TITLE : parsedSection.title();
                BigDecimal hint = parsedSection.pointsPerItem() == null
                        ? BigDecimal.ONE : parsedSection.pointsPerItem();
                PaperSection section = existingByTitle.get(title);
                if (section == null) {
                    section = new PaperSection(UUID.randomUUID(), paperId, title, ++position, hint);
                    sections.save(section);
                    existingByTitle.put(title, section);
                }
                fillSection(paperId, section, parsedSection.items(), existingNumbers, warnings,
                        imported, missingAnswers);
                summary.add(new ImportSection(title, parsedSection.items().size(), section.getPointsPerItem()));
            }
        }
        return new ImportRows(imported.size(), missingAnswers, imported, summary, warnings);
    }

    private void fillSection(UUID paperId, PaperSection section, List<PaperTextParser.ParsedItem> parsedItems,
                             Set<Integer> existingNumbers, List<String> warnings, List<ImportedItem> imported,
                             List<Integer> missingAnswers) {
        BigDecimal fallback = section.getPointsPerItem() == null
                ? BigDecimal.ONE : section.getPointsPerItem();
        int position = items.findByPaperSectionIdOrderByPosition(section.getId()).stream()
                .mapToInt(ObjectiveItem::getPosition).max().orElse(0);
        for (PaperTextParser.ParsedItem parsedItem : parsedItems) {
            if (existingNumbers.contains(parsedItem.number())) {
                warnings.add("题号 " + parsedItem.number() + " 已存在，已跳过（不覆盖已有题项）");
                continue;
            }
            String answerKey = parsedItem.answerKey();
            QuestionType type = parsedItem.questionType() != null
                    ? parsedItem.questionType()
                    : (parsedItem.options() == null
                            ? QuestionType.FILL_BLANK
                            : (answerKey != null && answerKey.length() > 1
                                    ? QuestionType.MULTIPLE_CHOICE : QuestionType.SINGLE_CHOICE));
            BigDecimal points = parsedItem.points() == null ? fallback : parsedItem.points();
            var item = new ObjectiveItem(UUID.randomUUID(), paperId, section.getId(), parsedItem.number(),
                    ++position, type, parsedItem.stem(), parsedItem.options(), answerKey, points);
            if (type == QuestionType.ESSAY) {
                item.setRubricMarkdown(parsedItem.rubricMarkdown() == null || parsedItem.rubricMarkdown().isBlank()
                        ? null : parsedItem.rubricMarkdown());
                item.setReferenceMarkdown(parsedItem.referenceMarkdown() == null
                        || parsedItem.referenceMarkdown().isBlank() ? null : parsedItem.referenceMarkdown());
            }
            items.save(item);
            existingNumbers.add(parsedItem.number());
            // 写作题由 rubric 判分，没有「标准答案」一说，不进缺答案清单
            if (type != QuestionType.ESSAY && (answerKey == null || answerKey.isBlank())) {
                missingAnswers.add(parsedItem.number());
            }
            imported.add(new ImportedItem(parsedItem.number(), parsedItem.stem(), parsedItem.options(),
                    answerKey, points, type.name()));
        }
    }

    private PaperSection resolveSection(UUID paperId, UUID sectionId) {
        List<PaperSection> all = sections.findByPaperIdOrderByPosition(paperId);
        if (sectionId == null) {
            return all.stream().findFirst()
                    .orElseThrow(() -> ApiException.badRequest("请先创建部分"));
        }
        return all.stream().filter(s -> s.getId().equals(sectionId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("指定的部分不属于这张试卷"));
    }

    @PostMapping("/{id}/criteria")
    @Transactional
    public void addCriteria(@PathVariable UUID id, @RequestBody @Valid CreateCriteria body,
                            @RequestParam(defaultValue = "false") boolean confirm,
                            @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = editable(id, confirm, me, "add_criteria");
        UUID sectionId = body.sectionId();
        if (sectionId == null) {
            sectionId = sections.findByPaperIdOrderByPosition(id).stream().findFirst()
                    .orElseThrow(() -> ApiException.badRequest("请先创建部分")).getId();
        }
        int pos = criteria.findByPaperIdOrderByPosition(id).size() + 1;
        criteria.save(new SectionCriteria(UUID.randomUUID(), id, sectionId, body.name().trim(),
                body.maxPoints(), body.description(), pos));
        recalcTotal(p);
    }

    @PutMapping("/{id}/rubric")
    @Transactional
    public PaperView rubric(@PathVariable UUID id, @RequestBody Rubric body,
                            @RequestParam(defaultValue = "false") boolean confirm,
                            @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = editable(id, confirm, me, "update_rubric");
        p.setRubricMarkdown(body.rubricMarkdown());
        p.setGradingPromptTemplate(body.gradingPromptTemplate());
        return view(p);
    }

    /**
     * 发布试卷（D-34 / AC-4.26③、FR-148）：服务端三条复校验，API 直调同样被拦。
     *
     * <p>① {@code total_points > 0}；② 非 {@code ESSAY} 题项没有空的标准答案；
     * ③ {@code target_total_points} 非空时合计必须等于目标（容差 0.001，目标为空则跳过）。
     * 失败一律用消息 key，缺答案的题号放进 {@link ApiError#detail()}，前端展示 message + detail。</p>
     *
     * <p>方法返回 {@code ResponseEntity} 是因为错误要带 detail（共享的
     * {@code GlobalExceptionHandler} 按 {@code ApiException} 只能给出 message）；
     * 成功仍是 200 + 原样的 {@link PaperView}。</p>
     */
    @PostMapping("/{id}/publish")
    @Transactional
    public ResponseEntity<?> publish(@PathVariable UUID id,
                                     @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = load(id);
        requireAuthor(p, me);
        if (p.getTotalPoints() == null || p.getTotalPoints().signum() <= 0) {
            return publishFailure("error.paper.emptyTotal", null);
        }
        List<Integer> missing = new ArrayList<>();
        for (ObjectiveItem item : items.findByPaperIdOrderByNumber(id)) {
            if (item.getQuestionType() == QuestionType.ESSAY) {
                continue;
            }
            if (item.getAnswerKey() == null || item.getAnswerKey().isBlank()) {
                missing.add(item.getNumber());
            }
        }
        if (!missing.isEmpty()) {
            return publishFailure("error.paper.missingAnswers", numbers(missing));
        }
        BigDecimal target = p.getTargetTotalPoints();
        if (target != null && p.getTotalPoints().subtract(target).abs().compareTo(TOTAL_TOLERANCE) > 0) {
            return publishFailure("error.paper.targetTotalMismatch",
                    "total=" + p.getTotalPoints().stripTrailingZeros().toPlainString()
                            + ",target=" + target.stripTrailingZeros().toPlainString());
        }
        p.publish();
        audit.record(AuditEvent.of(me.id(), "paper:publish", "paper", id.toString()));
        return ResponseEntity.ok(view(p));
    }

    /** T196：逐题改题型。单选 ↔ 多选互转只规范标准答案，不触碰已存在的学生作答。 */
    @PutMapping("/{id}/items/{number}/question-type")
    @Transactional
    public ItemView setQuestionType(@PathVariable UUID id, @PathVariable int number,
                                    @RequestBody @Valid SetQuestionType body,
                                    @RequestParam(defaultValue = "false") boolean confirm,
                                    @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = editable(id, confirm, me, "change_question_type");
        ObjectiveItem item = items.findByPaperIdAndNumber(id, number)
                .orElseThrow(() -> ApiException.notFound("题号 " + number + " 不存在"));
        applyType(item, body.questionType());
        return itemView(item);
    }

    /** T196：按题号区间批量设定题型。 */
    @PostMapping("/{id}/items/question-type/batch")
    @Transactional
    public BatchResult batchQuestionType(@PathVariable UUID id,
                                         @RequestBody @Valid BatchQuestionType body,
                                         @RequestParam(defaultValue = "false") boolean confirm,
                                         @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = editable(id, confirm, me, "batch_question_type");
        if (body.fromNumber() > body.toNumber()) {
            throw ApiException.badRequest("起始题号不能大于结束题号");
        }
        int updated = 0;
        for (ObjectiveItem item : items.findByPaperIdOrderByNumber(id)) {
            if (item.getNumber() >= body.fromNumber() && item.getNumber() <= body.toNumber()) {
                applyType(item, body.questionType());
                updated++;
            }
        }
        if (updated == 0) {
            throw ApiException.badRequest("该区间内没有题目");
        }
        return new BatchResult(updated, body.questionType().name());
    }

    /** T196：设置多选题计分策略（仅 MULTIPLE_CHOICE 有意义）。 */
    @PutMapping("/{id}/items/{number}/scoring-strategy")
    @Transactional
    public ItemView setScoringStrategy(@PathVariable UUID id, @PathVariable int number,
                                       @RequestBody @Valid SetScoringStrategy body,
                                       @RequestParam(defaultValue = "false") boolean confirm,
                                       @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = editable(id, confirm, me, "scoring_strategy");
        ObjectiveItem item = items.findByPaperIdAndNumber(id, number)
                .orElseThrow(() -> ApiException.notFound("题号 " + number + " 不存在"));
        if (item.getQuestionType() != QuestionType.MULTIPLE_CHOICE) {
            throw ApiException.badRequest("计分策略只对多选题有效");
        }
        item.setScoringStrategy(parseStrategy(body.scoringStrategy()));
        return itemView(item);
    }

    /** T054：发布后改结构必须显式 confirm，并写一条审计；普通编辑在发布前不受影响。 */
    private Paper editable(UUID id, boolean confirm, CurrentUser me, String action) {
        Paper p = load(id);
        if (p.getStatus() == PaperStatus.PUBLISHED) {
            if (!confirm) {
                throw ApiException.conflict("已发布的试卷内容已锁定；确认修改请带 confirm=true");
            }
            audit.record(new AuditEvent(me.id(), "paper:edited_after_publish", "paper", id.toString(),
                    java.util.Map.of("action", action, "rubricVersion", p.getRubricVersion())));
        }
        return p;
    }

    /**
     * 「生成考题」的入口闸门（D-34 / AC-4.24③）：已发布卷内容锁定，**一律 409**，
     * 带不带 {@code confirm} 都一样——要重建请先复制为新卷（plan §20.2 状态机）。
     *
     * <p>与其它写操作的 {@link #editable} 刻意分开：那条路径允许
     * {@code confirm=true} 改已发布卷，而重建会整体替换题项，不能沿用。</p>
     */
    private Paper importable(UUID id, CurrentUser me) {
        Paper p = load(id);
        requireAuthor(p, me);
        if (p.getStatus() == PaperStatus.PUBLISHED) {
            throw ApiException.conflict("error.paper.publishedLocked");
        }
        return p;
    }

    /**
     * 替换重建前把「将被删除的部分 / 题项 / 评分项」序列化成一条快照（AC-4.24⑥）。
     *
     * <p>与删除在同一事务里：事务回滚则快照也不留。写完后按 {@code created_at desc}
     * 裁到最近 {@value #SNAPSHOT_KEEP} 条，旧的不留（只用于误删恢复，不是历史版本）。</p>
     *
     * <p>没有注入快照仓储只可能是旧构造重载（测试/内部调用）：这种情况下宁可报错，
     * 也不能悄悄删掉老师的内容而不留恢复路径。</p>
     */
    private void writeSnapshot(Paper p, String reason) {
        if (snapshots == null) {
            throw new IllegalStateException("replace requires a PaperContentSnapshotRepository");
        }
        UUID paperId = p.getId();
        List<SnapshotSection> sectionList = sections.findByPaperIdOrderByPosition(paperId).stream()
                .map(s -> new SnapshotSection(s.getId().toString(), s.getTitle(), s.getPosition(),
                        s.getPointsPerItem()))
                .toList();
        List<SnapshotItem> itemList = items.findByPaperIdOrderByNumber(paperId).stream()
                .map(i -> new SnapshotItem(i.getId().toString(), i.getNumber(), i.getPosition(),
                        i.getQuestionType().name(), i.getStem(), i.getOptions(), i.getAnswerKey(),
                        i.getPoints(), i.getScoringStrategy().name(), i.getRubricMarkdown(),
                        i.getReferenceMarkdown(), i.getPaperSectionId().toString()))
                .toList();
        List<SnapshotCriteria> criteriaList = criteria.findByPaperIdOrderByPosition(paperId).stream()
                .map(c -> new SnapshotCriteria(c.getId().toString(), c.getName(), c.getMaxPoints(),
                        c.getDescription(), c.getPosition(), c.getPaperSectionId().toString()))
                .toList();
        String payload;
        try {
            payload = JSON.writeValueAsString(new SnapshotPayload(sectionList, itemList, criteriaList));
        } catch (JacksonException ex) {
            // 只序列化自己的小 record，真失败说明代码有问题：500 比「静默删内容」安全。
            throw new IllegalStateException("snapshot payload serialization failed", ex);
        }
        snapshots.save(new PaperContentSnapshot(UUID.randomUUID(), paperId, Instant.now(clock), reason, payload));
        List<PaperContentSnapshot> all = snapshots.findByPaperIdOrderByCreatedAtDesc(paperId);
        if (all.size() > SNAPSHOT_KEEP) {
            snapshots.deleteAll(all.subList(SNAPSHOT_KEEP, all.size()));
        }
    }

    /**
     * 抽取结果里的文本（按行拼回）：文件导入时它就是「提交的原文」。
     *
     * <p>.md 在这里已被 {@code MarkdownSyntax} 剥掉排版记号、docx / xlsx 本来就只有抽出来的段落，
     * 所以存的是**实际送进解析器的那份文本**——保证「回填编辑器 → 再生成」得到同样的题项。</p>
     */
    private static String textOf(DocumentTextExtractor.ExtractedDocument document) {
        StringJoiner joiner = new StringJoiner("\n");
        for (DocumentTextExtractor.SourceLine line : document.lines()) {
            joiner.add(line.text() == null ? "" : line.text());
        }
        return joiner.toString();
    }

    /**
     * 发布校验失败的统一出口：message 走消息 key（与 {@code ApiException} 同一条本地化链路），
     * detail 给前端做定位（缺答案的题号 / 合计与目标）。
     */
    private ResponseEntity<ApiError> publishFailure(String key, String detail) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.BAD_REQUEST.name(), text(key), detail));
    }

    /** 消息 key → 当前请求语言的文案；单测没注入 MessageSource 时原样返回 key。 */
    private String text(String key) {
        if (messages == null) {
            return key;
        }
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }

    /** 题号列表的稳定文案（"3, 7, 12"）；用 ASCII 分隔符，免得进 i18n 检查。 */
    private static String numbers(List<Integer> numbers) {
        StringJoiner joiner = new StringJoiner(", ");
        for (Integer number : numbers) {
            joiner.add(String.valueOf(number));
        }
        return joiner.toString();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** 目标总分不允许负数：负数会让「合计 == 目标」这条闸门永远不可能通过。 */
    private static BigDecimal requireNonNegativeTarget(BigDecimal target) {
        if (target.signum() < 0) {
            throw ApiException.badRequest("error.paper.targetNegative");
        }
        return target;
    }

    private void applyType(ObjectiveItem item, QuestionType type) {
        item.setQuestionType(type);
        String key = item.getAnswerKey();
        if (key == null) {
            return;
        }
        String letters = key.toUpperCase().replaceAll("[^A-Z]", "");
        if (type == QuestionType.MULTIPLE_CHOICE) {
            item.setAnswerKey(letters.isEmpty() ? key : sortedLetters(letters));
        } else if (type == QuestionType.SINGLE_CHOICE && !letters.isEmpty()) {
            item.setAnswerKey(letters.substring(0, 1));
        }
    }

    private static String sortedLetters(String letters) {
        char[] chars = letters.toCharArray();
        java.util.Arrays.sort(chars);
        return new String(chars).replaceAll("(.)(?=.*\\1)", "");
    }

    private static ScoringStrategy parseStrategy(String value) {
        if (value == null || value.isBlank()) {
            return ScoringStrategy.ALL_OR_NOTHING;
        }
        try {
            return ScoringStrategy.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("不支持的多选计分策略：" + value);
        }
    }

    private static ItemView itemView(ObjectiveItem i) {
        return new ItemView(i.getId().toString(), i.getNumber(), i.getQuestionType().name(),
                i.getStem(), i.getOptions(), i.getAnswerKey(), i.getPoints(),
                i.getScoringStrategy().name(), i.getRubricMarkdown(), i.getReferenceMarkdown());
    }

    /**
     * D-24 / D-25：题项级 rubric 与参考范文只允许写在写作题上。
     * 传 null 表示「不修改」，传空串表示「清空」。
     */
    private static void applyEssayText(ObjectiveItem item, String rubricMarkdown, String referenceMarkdown) {
        if (item.getQuestionType() != QuestionType.ESSAY) {
            boolean anyRubric = rubricMarkdown != null && !rubricMarkdown.isBlank();
            boolean anyReference = referenceMarkdown != null && !referenceMarkdown.isBlank();
            if (anyRubric || anyReference) {
                throw ApiException.badRequest("error.paper.essayOnly");
            }
            return;
        }
        if (rubricMarkdown != null) {
            item.setRubricMarkdown(rubricMarkdown.isBlank() ? null : rubricMarkdown);
        }
        if (referenceMarkdown != null) {
            item.setReferenceMarkdown(referenceMarkdown.isBlank() ? null : referenceMarkdown);
        }
    }

    private PaperSection requireSection(UUID paperId, UUID sectionId) {
        PaperSection section = sections.findById(sectionId)
                .orElseThrow(() -> ApiException.notFound("部分不存在"));
        if (!section.getPaperId().equals(paperId)) {
            throw ApiException.notFound("部分不存在");
        }
        return section;
    }

    /** 写操作只允许作者本人或管理员；越界一律 404，不泄漏存在性。 */
    private void requireAuthor(Paper p, CurrentUser me) {
        if (me == null || me.is("ADMIN")) {
            return;
        }
        if (!p.getAuthorId().equals(me.id())) {
            throw ApiException.notFound("试卷不存在");
        }
    }

    private void recalcTotal(Paper p) {
        BigDecimal sum = items.findByPaperIdOrderByNumber(p.getId()).stream()
                .map(ObjectiveItem::getPoints).reduce(BigDecimal.ZERO, BigDecimal::add);
        sum = sum.add(criteria.findByPaperIdOrderByPosition(p.getId()).stream()
                .map(SectionCriteria::getMaxPoints).reduce(BigDecimal.ZERO, BigDecimal::add));
        p.setTotalPoints(sum);
    }

    private Paper load(UUID id) {
        return papers.findById(id).orElseThrow(() -> ApiException.notFound("试卷不存在"));
    }

    private PaperView view(Paper p) {
        return new PaperView(p.getId().toString(), p.getTitle(), p.getTotalPoints(),
                p.getVersion(), p.getRubricVersion(), p.getStatus().name(), p.getDefaultDurationMinutes(),
                usage.assignmentCount(p.getId()), p.getAuthorName(), p.getReviewerName(),
                p.getTargetTotalPoints());
    }
}
