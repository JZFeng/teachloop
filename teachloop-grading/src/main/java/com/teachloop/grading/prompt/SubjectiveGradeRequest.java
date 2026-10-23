package com.teachloop.grading.prompt;

import com.teachloop.grading.service.TextLengthCounter;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次主观题判分调用所需的**全部**输入（plan §6）。
 *
 * <p>这个类型是隐私边界：它**没有** {@code answerKey}、{@code objectiveTotal}、{@code answers}、
 * {@code studentId}、{@code studentName} 字段，因此 PromptBuilder 在编译期就拿不到这些数据。
 * 新增字段前先问一句：这是模型判主观题必需的吗？
 */
public record SubjectiveGradeRequest(
        List<Item> items,
        String studentText,
        Integer wordCount,
        Integer paragraphCount,
        Integer durationSeconds,
        List<Criterion> criteria,
        String rubricMarkdown,
        String feedbackLanguage,
        String instructions,
        boolean binaryRubric,
        int lengthMin,
        int lengthMax,
        String paragraphPolicy,
        boolean paragraphBoundarySensitive,
        String anchors) {

    public static final String DEFAULT_LANGUAGE = "zh-CN";

    public SubjectiveGradeRequest {
        items = items == null ? List.of() : List.copyOf(items);
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        studentText = studentText == null ? "" : studentText;
        feedbackLanguage = feedbackLanguage == null || feedbackLanguage.isBlank()
                ? DEFAULT_LANGUAGE
                : feedbackLanguage;
        instructions = instructions == null ? "" : instructions;
        paragraphPolicy = paragraphPolicy == null || paragraphPolicy.isBlank()
                ? "policy_default" : paragraphPolicy;
        if (lengthMin <= 0) {
            lengthMin = 1;
        }
        if (lengthMax < lengthMin) {
            lengthMax = lengthMin;
        }
        anchors = anchors == null ? "" : anchors;
    }

    /** 一道主观题（作文/简答），只带模型判分需要的字段。 */
    public record Item(int number, String questionType, BigDecimal points, String stem) {
        public Item {
            Objects.requireNonNull(points, "points");
        }
    }

    /** rubric 中一个评分项的名称与满分。 */
    public record Criterion(String name, BigDecimal maxPoints) {
        public Criterion {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(maxPoints, "maxPoints");
        }
    }

    /** 试卷主观部分满分：所有主观题分值之和。 */
    public BigDecimal subjectiveMax() {
        return items.stream()
                .map(Item::points)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * 从跨模块端口快照组装；只取主观题（ESSAY），不读取 {@link Ports.WorkItem#answers()}。
     *
     * <p>客观指标（词数、段落数）目前由正文计算：{@code submissions} 的 metrics 还没有通过
     * {@code WorkItem} 暴露。时长暂时为 {@code null}。
     */
    /**
     * 一道作文的请求（D-26）：本题正文 + 本题 rubric + 本题满分与长度区间。
     *
     * <p>考试作文的正文在 {@code answers[]} 里、不在 {@code content_text}；独立作业反过来。
     * 正文在这里取出后只以字符串进请求，记录类本身仍不含 {@code answers} / {@code answerKey}。</p>
     */
    public static SubjectiveGradeRequest forEssay(Ports.WorkItem work, Ports.ItemSpec essay) {
        return forEssay(work, essay, EssayRubricWeights.DEFAULT);
    }

    /**
     * 同上，但允许指定默认评分比例（D-28）。题项级 rubric 与试卷级 rubric 都存在时优先用它们；
     * 两级都没有时才按 {@code weights} 合成「内容 + 语法与拼写」两项默认 rubric。
     */
    public static SubjectiveGradeRequest forEssay(Ports.WorkItem work, Ports.ItemSpec essay,
                                                  EssayRubricWeights weights) {
        String text = essayText(work, essay);
        String rubric = essay.rubricMarkdown() == null || essay.rubricMarkdown().isBlank()
                ? work.rubricMarkdown() : essay.rubricMarkdown();
        if (rubric == null || rubric.isBlank() || EssayRubricWeights.isLegacyDefault(rubric)) {
            BigDecimal total = essay.points() == null ? BigDecimal.ZERO : essay.points();
            rubric = (weights == null ? EssayRubricWeights.DEFAULT : weights)
                    .defaultMarkdown(total, isEnglish(work.feedbackLanguage()));
        }
        BigDecimal max = essay.points() == null ? BigDecimal.ZERO : essay.points();
        List<Criterion> criteria = RubricCriteria.parse(rubric, max, work.feedbackLanguage());
        int[] range = lengthRange(rubric);
        String instructions = essay.stem() == null ? "" : essay.stem().strip();
        return new SubjectiveGradeRequest(List.of(new Item(essay.number(), essay.questionType(), max,
                        instructions)), text, countWords(text),
                TextLengthCounter.count(text, "policy_default"), null,
                criteria, rubric, work.feedbackLanguage(), instructions,
                binaryRubric(rubric), range[0], range[1], "policy_default",
                TextLengthCounter.boundarySensitive(text, range[0], range[1]), work.anchors());
    }

    /**
     * 一道作文的正文：考试取 {@code answers[]} 里该题号的作答；全卷只有 1 道 ESSAY 且没有该行时
     * 回退 {@code content_text}（独立作业路径）。
     */
    public static String essayText(Ports.WorkItem work, Ports.ItemSpec essay) {
        long essays = work.items() == null ? 0 : work.items().stream()
                .filter(i -> "ESSAY".equals(i.questionType())).count();
        // 独立作业只有一篇、正文在 content_text 里；考试作文在 answers[] 里（content_text 为空）
        if (essays == 1 && work.contentText() != null && !work.contentText().isBlank()) {
            return work.contentText().strip();
        }
        if (work.answers() != null) {
            for (Ports.AnswerRow row : work.answers()) {
                if (row.number() == essay.number() && row.answer() != null && !row.answer().isBlank()) {
                    return row.answer().strip();
                }
            }
        }
        return "";
    }

    public static SubjectiveGradeRequest from(Ports.WorkItem item) {
        List<Item> essays = item.items().stream()
                .filter(i -> "ESSAY".equals(i.questionType()))
                .map(i -> new Item(i.number(), i.questionType(), i.points(), i.stem()))
                .toList();
        BigDecimal essayMax = essays.stream()
                .map(Item::points)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String text = item.contentText() == null ? "" : item.contentText();
        List<Criterion> criteria = RubricCriteria.parse(item.rubricMarkdown(), essayMax, item.feedbackLanguage());
        String instructions = essays.stream()
                .map(Item::stem)
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
        int[] range = lengthRange(item.rubricMarkdown());
        return new SubjectiveGradeRequest(essays, text, countWords(text),
                TextLengthCounter.count(text, "policy_default"), null,
                criteria, item.rubricMarkdown(), item.feedbackLanguage(), instructions,
                binaryRubric(item.rubricMarkdown()), range[0], range[1], "policy_default",
                TextLengthCounter.boundarySensitive(text, range[0], range[1]), item.anchors());
    }

    private static final Pattern LENGTH_RANGE = Pattern.compile(
            "(?i)(paragraphs?|段|minutes?|分钟)[^0-9]{0,12}(\\d+)\\s*[–\\-~至到]\\s*(\\d+)");
    private static final Pattern BINARY_RULE = Pattern.compile(
            "(?im)^\\s*[-*]?\\s*(?:\\*\\*)?(yes|no|是|否)(?:\\*\\*)?\\s*[:：]");

    private static int[] lengthRange(String rubric) {
        if (rubric != null) {
            Matcher m = LENGTH_RANGE.matcher(rubric);
            if (m.find()) {
                int min = Integer.parseInt(m.group(2));
                int max = Integer.parseInt(m.group(3));
                if (min > 0 && max >= min) {
                    return new int[] {min, max};
                }
            }
        }
        return new int[] {1, 4};
    }

    private static boolean isEnglish(String language) {
        return language != null && language.toLowerCase(java.util.Locale.ROOT).startsWith("en");
    }

    /** 二元 rubric（Yes/No、是/否）只允许 0 或满分；分档 rubric 不加这个限制。 */
    private static boolean binaryRubric(String rubric) {
        return rubric != null && BINARY_RULE.matcher(rubric).find();
    }

    static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int words = 0;
        for (String token : text.strip().split("\\s+")) {
            if (!token.isBlank()) {
                words++;
            }
        }
        return words;
    }

    static int countParagraphs(String text) {
        return TextLengthCounter.count(text == null ? "" : text, "policy_default");
    }
}
