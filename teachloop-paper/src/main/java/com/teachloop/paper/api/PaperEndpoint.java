package com.teachloop.paper.api;

import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.paper.domain.*;
import com.teachloop.paper.importing.AnswerTableParser;
import com.teachloop.paper.importing.DocumentTextExtractor;
import com.teachloop.paper.importing.ImportException;
import com.teachloop.paper.importing.PaperTextParser;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiError;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/papers")
public class PaperEndpoint {

    private final PaperRepository papers;
    private final PaperSectionRepository sections;
    private final ObjectiveItemRepository items;
    private final SectionCriteriaRepository criteria;
    private final Clock clock;
    private final Ports.PaperUsage usage;
    private final Ports.ObjectiveRecompute recompute;

    private final AuditPort audit;

    /** 旧测试/内部构造兼容：没有布置统计时按 0 处理。 */
    public PaperEndpoint(PaperRepository papers, PaperSectionRepository sections,
                         ObjectiveItemRepository items, SectionCriteriaRepository criteria,
                         AuditPort audit, Clock clock) {
        this(papers, sections, items, criteria, audit, clock, id -> 0, (paperId, keys) -> 0);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaperEndpoint(PaperRepository papers, PaperSectionRepository sections,
                         ObjectiveItemRepository items, SectionCriteriaRepository criteria,
                         AuditPort audit, Clock clock, Ports.PaperUsage usage,
                         Ports.ObjectiveRecompute recompute) {
        this.papers = papers;
        this.sections = sections;
        this.items = items;
        this.criteria = criteria;
        this.audit = audit;
        this.clock = clock;
        this.usage = usage;
        this.recompute = recompute;
    }

    public record CreatePaper(@NotBlank String title, Integer defaultDurationMinutes) {
    }

    public record CreateSection(@NotBlank String title, BigDecimal pointsPerItem) {
    }
    public record UpdateSection(String title, BigDecimal pointsPerItem) {
    }

    public record CreateItem(@NotNull Integer number, @NotNull QuestionType questionType, String stem,
                             String options, String answerKey, @NotNull BigDecimal points, UUID sectionId,
                             String scoringStrategy) {
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

    public record PaperView(String id, String title, BigDecimal totalPoints, int version, int rubricVersion,
                            String status, Integer defaultDurationMinutes, int assignmentCount) {
    }

    public record ItemView(String id, int number, String questionType, String stem, String options,
                           String answerKey, BigDecimal points, String scoringStrategy) {
    }

    public record SectionView(String id, String title, int position, BigDecimal pointsPerItem, List<ItemView> items) {
    }

    public record PaperDetail(PaperView paper, List<SectionView> sections, List<String> criteriaNames,
                              String rubricMarkdown, String gradingPromptTemplate) {
    }

    public record ImportedItem(int number, String stem, String options, String answerKey,
                               BigDecimal points, String questionType) {
    }

    /** 导入后每个部分落的题数，前端用它展示「文档结构被保留成了什么」。 */
    public record ImportSection(String title, int itemCount, BigDecimal pointsPerItem) {
    }

    public record UpdateItem(String stem, String options, String answerKey, BigDecimal points) {
    }

    public record AnswerTableRequest(@jakarta.validation.constraints.NotBlank String text) {
    }

    public record AnswerTableResult(int updated, List<Integer> unknownNumbers, List<String> warnings) {
    }

    public record ImportTextRequest(String text, UUID sectionId) {
    }

    public record ImportResult(int imported, List<Integer> missingAnswers, List<ImportedItem> items,
                               List<ImportSection> sections, List<String> warnings) {
    }

    @PostMapping
    @Transactional
    public PaperView create(@RequestBody @Valid CreatePaper body, @RequestAttribute("currentUser") CurrentUser me) {
        var p = new Paper(UUID.randomUUID(), body.title().trim(), body.defaultDurationMinutes(), me.id(), Instant.now(clock));
        papers.save(p);
        return view(p);
    }

    @GetMapping
    public List<PaperView> mine(@RequestAttribute("currentUser") CurrentUser me) {
        return papers.findByAuthorIdOrderByCreatedAtDesc(me.id()).stream().map(this::view).toList();
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
        return new PaperDetail(view(p), secs, crit, p.getRubricMarkdown(), p.getGradingPromptTemplate());
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
            for (ObjectiveItem item : items.findByPaperIdOrderByNumber(id)) {
                if (item.getQuestionType() == QuestionType.FILL_BLANK) {
                    freeText.add(item.getNumber());
                }
            }
            parsed = AnswerTableParser.parse(body.text(), freeText);
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
        return importFile(id, file, sectionId, false, me);
    }

    @PostMapping(path = "/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<?> importFile(@PathVariable UUID id,
                                       @RequestPart("file") MultipartFile file,
                                       @RequestParam(name = "sectionId", required = false) UUID sectionId,
                                       @RequestParam(defaultValue = "false") boolean confirm,
                                       @RequestAttribute("currentUser") CurrentUser me) {
        if (me != null && me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能导入试卷");
        }
        Paper p = editable(id, confirm, me, "import");
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择要导入的文件");
        }

        PaperTextParser.Result parsed;
        try {
            DocumentTextExtractor.ExtractedDocument document =
                    DocumentTextExtractor.extract(file.getOriginalFilename(), file.getBytes());
            parsed = PaperTextParser.parse(document.lines());
        } catch (ImportException ex) {
            return ResponseEntity.badRequest()
                    .body(new ApiError(ErrorCode.BAD_REQUEST.name(), "导入失败", ex.getMessage()));
        } catch (IOException ex) {
            return ResponseEntity.badRequest()
                    .body(new ApiError(ErrorCode.BAD_REQUEST.name(), "导入失败",
                            "读取上传文件失败：" + ex.getMessage()));
        }

        return ResponseEntity.ok(createItems(id, sectionId, parsed));
    }

    /** 粘贴整卷文本导入（与文件导入同一解析器与创建逻辑）。 */
    @PostMapping("/{id}/import/text")
    @Transactional
    public ResponseEntity<?> importText(@PathVariable UUID id, @RequestBody ImportTextRequest body,
                                        @RequestAttribute("currentUser") CurrentUser me) {
        if (me != null && me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能导入试卷");
        }
        Paper p = editable(id, false, me, "import_text");
        if (body.text() == null || body.text().isBlank()) {
            throw ApiException.badRequest("请粘贴试卷文字");
        }
        // 与文件导入走同一套预处理：老师经常把整卷 .md 直接粘进来，不剥掉标题/加粗/表格记号，
        // 「第X节」这类分节认不出来，题号就会串（2026-10-22 真实卷踩过）。
        PaperTextParser.Result parsed;
        try {
            DocumentTextExtractor.ExtractedDocument document = DocumentTextExtractor.extract(
                    "pasted.md", body.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            parsed = PaperTextParser.parse(document.lines());
        } catch (ImportException ex) {
            return ResponseEntity.badRequest()
                    .body(new ApiError(ErrorCode.BAD_REQUEST.name(), "导入失败", ex.getMessage()));
        }
        return ResponseEntity.ok(createItems(id, body.sectionId(), parsed));
    }

    /**
     * 把解析结果落成题项（T186 / T217）。
     *
     * <p>文档里识别出多个「第X部分」时，按文档结构新建部分、逐题分值跟着走，
     * 此时 {@code targetSectionId} 不再使用；只有一个部分（或没写部分）时，
     * 仍然导入到选中的目标部分，与旧行为一致。</p>
     */
    private ImportResult createItems(UUID paperId, UUID targetSectionId, PaperTextParser.Result parsed) {
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
                String title = parsedSection.title().isBlank() ? "综合" : parsedSection.title();
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
        recalcTotal(load(paperId));
        return new ImportResult(imported.size(), missingAnswers, imported, summary, warnings);
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
            QuestionType type = parsedItem.options() == null
                    ? QuestionType.FILL_BLANK
                    : (answerKey != null && answerKey.length() > 1
                            ? QuestionType.MULTIPLE_CHOICE : QuestionType.SINGLE_CHOICE);
            BigDecimal points = parsedItem.points() == null ? fallback : parsedItem.points();
            var item = new ObjectiveItem(UUID.randomUUID(), paperId, section.getId(), parsedItem.number(),
                    ++position, type, parsedItem.stem(), parsedItem.options(), answerKey, points);
            items.save(item);
            existingNumbers.add(parsedItem.number());
            if (answerKey == null || answerKey.isBlank()) {
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

    @PostMapping("/{id}/publish")
    @Transactional
    public PaperView publish(@PathVariable UUID id,
                             @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = load(id);
        if (p.getTotalPoints().signum() <= 0) {
            throw ApiException.badRequest("总分为 0，不能发布");
        }
        p.publish();
        audit.record(AuditEvent.of(me.id(), "paper:publish", "paper", id.toString()));
        return view(p);
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
                i.getScoringStrategy().name());
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
                usage.assignmentCount(p.getId()));
    }
}
