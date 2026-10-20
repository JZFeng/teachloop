package com.teachloop.paper.api;

import com.teachloop.shared.security.CurrentUser;
import com.teachloop.paper.domain.*;
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
import java.util.List;
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

    private final AuditPort audit;

    public PaperEndpoint(PaperRepository papers, PaperSectionRepository sections,
                         ObjectiveItemRepository items, SectionCriteriaRepository criteria,
                         AuditPort audit, Clock clock) {
        this.papers = papers;
        this.sections = sections;
        this.items = items;
        this.criteria = criteria;
        this.audit = audit;
        this.clock = clock;
    }

    public record CreatePaper(@NotBlank String title, Integer defaultDurationMinutes) {
    }

    public record CreateSection(@NotBlank String title, BigDecimal pointsPerItem) {
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

    public record PaperView(String id, String title, int totalPoints, int version, int rubricVersion,
                            String status, Integer defaultDurationMinutes) {
    }

    public record ItemView(String id, int number, String questionType, String stem, String options,
                           String answerKey, int points, String scoringStrategy) {
    }

    public record SectionView(String id, String title, int position, int pointsPerItem, List<ItemView> items) {
    }

    public record PaperDetail(PaperView paper, List<SectionView> sections, List<String> criteriaNames) {
    }

    public record ImportedItem(int number, String stem, String options, String answerKey) {
    }

    public record ImportResult(int imported, List<Integer> missingAnswers, List<ImportedItem> items,
                               List<String> warnings) {
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

    @GetMapping("/{id}")
    public PaperDetail detail(@PathVariable UUID id) {
        Paper p = load(id);
        List<SectionView> secs = sections.findByPaperIdOrderByPosition(id).stream()
                .map(s -> new SectionView(s.getId().toString(), s.getTitle(), s.getPosition(),
                        s.getPointsPerItem() == null ? 0 : s.getPointsPerItem().intValue(),
                        items.findByPaperSectionIdOrderByPosition(s.getId()).stream()
                                .map(i -> itemView(i))
                                .toList()))
                .toList();
        List<String> crit = criteria.findByPaperIdOrderByPosition(id).stream().map(SectionCriteria::getName).toList();
        return new PaperDetail(view(p), secs, crit);
    }

    @PostMapping("/{id}/sections")
    @Transactional
    public SectionView addSection(@PathVariable UUID id, @RequestBody @Valid CreateSection body,
                                  @RequestParam(defaultValue = "false") boolean confirm,
                                  @RequestAttribute("currentUser") CurrentUser me) {
        editable(id, confirm, me, "add_section");
        int pos = sections.findByPaperIdOrderByPosition(id).size() + 1;
        var s = new PaperSection(UUID.randomUUID(), id, body.title().trim(), pos, body.pointsPerItem());
        sections.save(s);
        return new SectionView(s.getId().toString(), s.getTitle(), pos,
                body.pointsPerItem() == null ? 0 : body.pointsPerItem().intValue(), List.of());
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
        int pos = items.findByPaperSectionIdOrderByPosition(sectionId).size() + 1;
        var item = new ObjectiveItem(UUID.randomUUID(), id, sectionId, body.number(), pos,
                body.questionType(), body.stem(), body.options(), body.answerKey(), body.points());
        item.setScoringStrategy(parseStrategy(body.scoringStrategy()));
        items.save(item);
        recalcTotal(p);
        return itemView(item);
    }

    /**
     * 从 .docx / .md / .txt / .xlsx 导入客观题项（T186）。
     *
     * <p>题目与答案写在同一个文档里也能一次导入：答案按题号自动配对，
     * 没配上的题照样创建，但题号列进 {@code missingAnswers}。</p>
     *
     * <p>只新增 {@code objective_items}：已存在的题号跳过（进 warnings，不覆盖）；
     * 不触碰已有题项。</p>
     */
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
        PaperSection section = resolveSection(id, sectionId);
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

        Set<Integer> existingNumbers = new HashSet<>();
        for (ObjectiveItem existing : items.findByPaperIdOrderByNumber(id)) {
            existingNumbers.add(existing.getNumber());
        }
        BigDecimal points = section.getPointsPerItem() == null ? BigDecimal.ONE : section.getPointsPerItem();
        int position = items.findByPaperSectionIdOrderByPosition(section.getId()).size();

        List<String> warnings = new ArrayList<>(parsed.warnings());
        List<ImportedItem> imported = new ArrayList<>();
        List<Integer> missingAnswers = new ArrayList<>();
        for (PaperTextParser.ParsedItem parsedItem : parsed.items()) {
            if (existingNumbers.contains(parsedItem.number())) {
                warnings.add("题号 " + parsedItem.number() + " 已存在，已跳过（不覆盖已有题项）");
                continue;
            }
            QuestionType type = parsedItem.options() == null
                    ? QuestionType.FILL_BLANK
                    : (parsedItem.answerKey() != null && parsedItem.answerKey().length() > 1
                            ? QuestionType.MULTIPLE_CHOICE : QuestionType.SINGLE_CHOICE);
            var item = new ObjectiveItem(UUID.randomUUID(), id, section.getId(), parsedItem.number(),
                    ++position, type, parsedItem.stem(), parsedItem.options(),
                    parsedItem.answerKey(), points);
            items.save(item);
            existingNumbers.add(parsedItem.number());
            if (parsedItem.answerKey() == null || parsedItem.answerKey().isBlank()) {
                missingAnswers.add(parsedItem.number());
            }
            imported.add(new ImportedItem(parsedItem.number(), parsedItem.stem(), parsedItem.options(),
                    parsedItem.answerKey()));
        }
        recalcTotal(p);
        return ResponseEntity.ok(new ImportResult(imported.size(), missingAnswers, imported, warnings));
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
                i.getStem(), i.getOptions(), i.getAnswerKey(), i.getPoints().intValue(),
                i.getScoringStrategy().name());
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
        return new PaperView(p.getId().toString(), p.getTitle(), p.getTotalPoints().intValue(),
                p.getVersion(), p.getRubricVersion(), p.getStatus().name(), p.getDefaultDurationMinutes());
    }
}
