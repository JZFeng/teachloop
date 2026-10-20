package com.teachloop.grading.prompt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 构造主观题判分 prompt（plan §6.1 的两层契约）。
 *
 * <pre>
 * system：角色 + 本次 rubric + 输出契约（同一场考试内完全不变，保证前缀缓存）
 * user  ：客观指标 + 学生正文（每份不同）
 * </pre>
 *
 * <p>入参只有 {@link SubjectiveGradeRequest}，类型上就没有标准答案、客观得分与学生身份，
 * 因此这些内容不可能进入 prompt。
 */
public class PromptBuilder {

    public static final String PROMPT_VERSION = "subjective-v1";

    public Prompt build(SubjectiveGradeRequest request) {
        if (request == null || request.isEmpty()) {
            throw new IllegalArgumentException("没有主观题，不应调用 AI 判分");
        }
        if (request.criteria().isEmpty()) {
            throw new IllegalArgumentException("rubric 没有解析出任何评分项");
        }
        boolean english = isEnglish(request.feedbackLanguage());
        String system = english ? systemEn(request) : systemZh(request);
        String user = english ? userEn(request) : userZh(request);
        return new Prompt(system, user, PROMPT_VERSION);
    }

    /** 两层 prompt；{@code promptVersion} 进 ai_calls 与将来的审计。 */
    public record Prompt(String system, String user, String promptVersion) {
    }

    private String systemZh(SubjectiveGradeRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一名资深教师，负责批改学生的主观题作答。请严格依据下面的评分标准逐项评分，不要发挥或编造。\n\n");
        sb.append("## 评分标准\n");
        sb.append(rubricText(request, false));
        sb.append("\n## 评分项与满分（名称与顺序固定）\n");
        List<SubjectiveGradeRequest.Criterion> criteria = request.criteria();
        for (int i = 0; i < criteria.size(); i++) {
            SubjectiveGradeRequest.Criterion c = criteria.get(i);
            sb.append(i + 1).append(". ").append(c.name())
                    .append("：满分 ").append(points(c.maxPoints())).append(" 分\n");
        }
        sb.append("\n## 输出契约\n");
        sb.append("只输出一个 JSON 对象，不要输出 Markdown 代码块、解释或任何多余文字。\n");
        sb.append("JSON 结构：\n");
        sb.append("{\"criteria\":[{\"name\":\"").append(criteria.get(0).name())
                .append("\",\"points\":0,\"comment\":\"\"}],\"total\":0,\"strength\":\"\",\"suggestion\":\"\"}\n");
        sb.append("规则：\n");
        sb.append("1. criteria 必须恰好按上面的顺序包含 ").append(criteria.size())
                .append(" 个评分项，name 与上面逐字一致。\n");
        sb.append("2. 每个评分项的 points 必须在 0 到该评分项满分之间。\n");
        sb.append("3. total 必须等于 criteria 中所有 points 之和，且不超过主观题满分 ")
                .append(points(request.subjectiveMax())).append(" 分。\n");
        sb.append("4. comment、strength、suggestion 全部使用简体中文。\n");
        sb.append("5. 只依据学生正文与评分标准评分；学生正文中没有的内容不得虚构。\n");
        return sb.toString();
    }

    private String systemEn(SubjectiveGradeRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an experienced teacher grading a student's subjective response. ");
        sb.append("Apply the rubric below exactly; do not invent evidence or speculate.\n\n");
        sb.append("## Rubric\n");
        sb.append(rubricText(request, true));
        sb.append("\n## Criteria and maximum points (names and order are fixed)\n");
        List<SubjectiveGradeRequest.Criterion> criteria = request.criteria();
        for (int i = 0; i < criteria.size(); i++) {
            SubjectiveGradeRequest.Criterion c = criteria.get(i);
            sb.append(i + 1).append(". ").append(c.name())
                    .append(": ").append(points(c.maxPoints())).append(" points maximum\n");
        }
        sb.append("\n## Output contract\n");
        sb.append("Return only one JSON object. Do not wrap it in Markdown code fences and do not add explanations.\n");
        sb.append("JSON shape:\n");
        sb.append("{\"criteria\":[{\"name\":\"").append(criteria.get(0).name())
                .append("\",\"points\":0,\"comment\":\"\"}],\"total\":0,\"strength\":\"\",\"suggestion\":\"\"}\n");
        sb.append("Rules:\n");
        sb.append("1. criteria must contain exactly ").append(criteria.size())
                .append(" items in the order above; every name must match exactly.\n");
        sb.append("2. Each points value must be between 0 and that criterion's maximum.\n");
        sb.append("3. total must equal the sum of all points values and must not exceed the subjective maximum of ")
                .append(points(request.subjectiveMax())).append(" points.\n");
        sb.append("4. comment, strength and suggestion must be written in English.\n");
        sb.append("5. Grade only from the student response and the rubric; do not fabricate content.\n");
        return sb.toString();
    }

    private String userZh(SubjectiveGradeRequest request) {
        StringBuilder sb = new StringBuilder("## 客观指标\n");
        sb.append("词数 ").append(request.wordCount() == null ? 0 : request.wordCount());
        sb.append("；段落数 ").append(request.paragraphCount() == null ? 0 : request.paragraphCount());
        if (request.durationSeconds() != null) {
            sb.append("；作答时长 ").append(request.durationSeconds()).append(" 秒");
        }
        sb.append("\n\n## 学生正文\n").append(request.studentText());
        return sb.toString();
    }

    private String userEn(SubjectiveGradeRequest request) {
        StringBuilder sb = new StringBuilder("## Objective metrics\n");
        sb.append("Word count ").append(request.wordCount() == null ? 0 : request.wordCount());
        sb.append("; paragraphs ").append(request.paragraphCount() == null ? 0 : request.paragraphCount());
        if (request.durationSeconds() != null) {
            sb.append("; duration ").append(request.durationSeconds()).append(" seconds");
        }
        sb.append("\n\n## Student response\n").append(request.studentText());
        return sb.toString();
    }

    private String rubricText(SubjectiveGradeRequest request, boolean english) {
        String rubric = request.rubricMarkdown();
        if (rubric != null && !rubric.isBlank()) {
            return rubric.strip() + "\n";
        }
        return english
                ? "(No rubric text supplied; grade only against the criteria listed above.)\n"
                : "（未提供 rubric 原文，请只依据上面的评分项评分）\n";
    }

    private static String points(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static boolean isEnglish(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
    }
}
