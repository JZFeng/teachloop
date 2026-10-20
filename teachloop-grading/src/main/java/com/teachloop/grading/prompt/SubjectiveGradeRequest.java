package com.teachloop.grading.prompt;

import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

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
        String feedbackLanguage) {

    public static final String DEFAULT_LANGUAGE = "zh-CN";

    public SubjectiveGradeRequest {
        items = items == null ? List.of() : List.copyOf(items);
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        studentText = studentText == null ? "" : studentText;
        feedbackLanguage = feedbackLanguage == null || feedbackLanguage.isBlank()
                ? DEFAULT_LANGUAGE
                : feedbackLanguage;
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
        return new SubjectiveGradeRequest(essays, text, countWords(text), countParagraphs(text), null,
                criteria, item.rubricMarkdown(), item.feedbackLanguage());
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
        if (text == null || text.isBlank()) {
            return 0;
        }
        int paragraphs = 0;
        for (String block : text.strip().split("\\R\\s*\\R")) {
            if (!block.isBlank()) {
                paragraphs++;
            }
        }
        return paragraphs;
    }
}
