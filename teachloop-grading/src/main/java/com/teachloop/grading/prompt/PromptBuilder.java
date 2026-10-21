package com.teachloop.grading.prompt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 构造主观题判分 prompt（plan §6.1 的 v2 两层契约）。
 *
 * <pre>
 * system：角色 + 题目要求 + 完整 rubric + 评分政策 + 输出契约（同一场考试内完全不变）
 * user  ：客观指标（词数、段数、政策、边界）+ 本次反馈语言 + 学生正文（每份不同）
 * </pre>
 *
 * <p>v2 相对 v1 的变化（2026-10-21）：
 * <ul>
 *   <li>把题目的 {@code instructions}/{@code stem} 放进 prompt——v1 完全没让模型看到题目要求；</li>
 *   <li>逐项评语必须引用正文原句，并新增 {@code overall} 总评；</li>
 *   <li>二元 rubric 明确只给 0 或满分；长度项以服务端段数为准；</li>
 *   <li>反馈语言放在 user 层，避免同班不同学生语言破坏 system 前缀缓存。</li>
 * </ul>
 *
 * <p>入参只有 {@link SubjectiveGradeRequest}，类型上就没有标准答案、客观得分与学生身份，
 * 因此这些内容不可能进入 prompt。
 */
public class PromptBuilder {

    public static final String PROMPT_VERSION = "subjective-v2";

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
        sb.append("你是一名有经验的 TESOL 教师与助教，负责给学生的一次主观题作答写第一轮评语。")
                .append("最终决定权在老师：你的输出是草稿，不是最终成绩。\n\n");
        sb.append("## 题目要求\n");
        sb.append(instructions(request, false)).append("\n\n");
        sb.append("## 评分标准（只按这一份判分）\n");
        sb.append(rubricText(request, false)).append("\n");
        if (hasAnchors(request)) {
            sb.append("## 参考范文（教师确认、已匿名；只作尺度示例，不要照抄）\n");
            sb.append(request.anchors().strip()).append("\n\n");
        }
        sb.append("## 评分项与满分（名称与顺序固定）\n");
        List<SubjectiveGradeRequest.Criterion> criteria = request.criteria();
        for (int i = 0; i < criteria.size(); i++) {
            SubjectiveGradeRequest.Criterion c = criteria.get(i);
            sb.append(i + 1).append(". ").append(c.name())
                    .append("：满分 ").append(points(c.maxPoints())).append(" 分\n");
        }
        sb.append("\n## 评分政策\n");
        sb.append("1. 只依据题目要求、评分标准与学生正文评分；学生正文里没有的内容不得虚构。\n");
        sb.append("2. 不因你是否同意学生的观点加减分；只判断论证与支持的质量。\n");
        if (request.binaryRubric()) {
            sb.append("3. 本 rubric 是二元判定：每一项只能给 0 分或该项满分，不得给 rubric 未定义的中间分。\n");
        } else {
            sb.append("3. 只能给 rubric 明确写出的档位分值，不得发明未定义的中间分。\n");
        }
        if (hasLengthCriterion(request)) {
            sb.append("4. 篇幅项以服务端 counted 的段数为准：共 ").append(request.paragraphCount())
                    .append(" 段（政策 ").append(request.paragraphPolicy())
                    .append("；边界敏感：").append(request.paragraphBoundarySensitive() ? "是" : "否")
                    .append("）。不要自行重新数段；在理由里只写段数与政策，不要猜测哪一段是多余的。\n");
        } else {
            sb.append("4. 不要额外增加 rubric 里没有的扣分项。\n");
        }
        sb.append("5. 每一项评语必须引用至少一处学生正文中的原句（逐字引用，不超过 15 个词），")
                .append("并说明该证据如何满足或不满足该项。\n");
        sb.append("6. 评语要具体、简短、可执行；每项 1–2 句，不重写学生全文。\n");
        sb.append("7. 总评 1–2 句；优点写一条具体且带证据的；建议写一条与最低分项相关的下一步。\n");
        sb.append("8. 全部输出使用简体中文。\n\n");
        sb.append("## 输出契约\n");
        sb.append("只输出一个 JSON 对象，不要输出 Markdown 代码块、解释或任何多余文字。\n");
        sb.append("JSON 结构：\n");
        sb.append(jsonSkeleton(criteria)).append("\n");
        sb.append("规则：\n");
        sb.append("1. criteria 必须恰好按上面的顺序包含 ").append(criteria.size())
                .append(" 个评分项，name 与上面逐字一致。\n");
        sb.append("2. 每个评分项的 points 必须符合评分政策；不能超过该项满分。\n");
        sb.append("3. total 必须等于 criteria 中所有 points 之和，且不超过主观题满分 ")
                .append(points(request.subjectiveMax())).append(" 分。\n");
        sb.append("4. comment、overall、strength、suggestion 全部使用简体中文；comment 必须含原文引用。\n");
        return sb.toString();
    }

    private String systemEn(SubjectiveGradeRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an experienced TESOL instructor and teaching assistant writing the first-pass ")
                .append("review of one student response. The teacher makes the final decision; your output ")
                .append("is a draft, not a final grade.\n\n");
        sb.append("## Assignment\n");
        sb.append(instructions(request, true)).append("\n\n");
        sb.append("## Rubric (grade only against this)\n");
        sb.append(rubricText(request, true)).append("\n");
        if (hasAnchors(request)) {
            sb.append("## Anchor examples (teacher-approved, anonymized; use only for calibration, never copy)\n");
            sb.append(request.anchors().strip()).append("\n\n");
        }
        sb.append("## Criteria and maximum points (names and order are fixed)\n");
        List<SubjectiveGradeRequest.Criterion> criteria = request.criteria();
        for (int i = 0; i < criteria.size(); i++) {
            SubjectiveGradeRequest.Criterion c = criteria.get(i);
            sb.append(i + 1).append(". ").append(c.name())
                    .append(": ").append(points(c.maxPoints())).append(" points maximum\n");
        }
        sb.append("\n## Grading policy\n");
        sb.append("1. Grade only from the assignment, the rubric and the response; do not invent evidence.\n");
        sb.append("2. Do not reward or penalize the viewpoint itself; judge the quality of the support.\n");
        if (request.binaryRubric()) {
            sb.append("3. This rubric is binary: award either 0 or the criterion's full points. ")
                    .append("Do not invent intermediate values.\n");
        } else {
            sb.append("3. Award only the band values the rubric defines; do not invent intermediate values.\n");
        }
        if (hasLengthCriterion(request)) {
            sb.append("4. Required Length is decided by the server count: ")
                    .append(request.paragraphCount()).append(" paragraphs (policy ")
                    .append(request.paragraphPolicy()).append("; boundary-sensitive: ")
                    .append(request.paragraphBoundarySensitive() ? "yes" : "no")
                    .append("). Do not recount; state the count and policy only, without guessing which paragraph is extra.\n");
        } else {
            sb.append("4. Do not add deductions that the rubric does not define.\n");
        }
        sb.append("5. Every criterion comment must quote at least one phrase verbatim from the response ")
                .append("(15 words or fewer) and explain how that evidence meets or misses the criterion.\n");
        sb.append("6. Be specific, concise and actionable: 1–2 sentences per comment; do not rewrite the response.\n");
        sb.append("7. overall is 1–2 sentences; strength is one specific, evidence-based point; ")
                .append("suggestion is one actionable next step tied to the lowest-scoring criterion.\n");
        sb.append("8. Write all feedback in English.\n\n");
        sb.append("## Output contract\n");
        sb.append("Return only one JSON object, with no Markdown fences and no other text.\n");
        sb.append("JSON shape:\n");
        sb.append(jsonSkeleton(criteria)).append("\n");
        sb.append("Rules:\n");
        sb.append("1. criteria must contain exactly ").append(criteria.size())
                .append(" items in the order above; every name must match exactly.\n");
        sb.append("2. Each points value must follow the grading policy and must not exceed that criterion's maximum.\n");
        sb.append("3. total must equal the sum of all points and must not exceed the subjective maximum of ")
                .append(points(request.subjectiveMax())).append(" points.\n");
        sb.append("4. comment, overall, strength and suggestion must be written in English; ")
                .append("every comment must contain a verbatim quote.\n");
        return sb.toString();
    }

    private String userZh(SubjectiveGradeRequest request) {
        StringBuilder sb = new StringBuilder("## 客观指标\n");
        sb.append("词数 ").append(request.wordCount() == null ? 0 : request.wordCount());
        sb.append("；段落数 ").append(request.paragraphCount() == null ? 0 : request.paragraphCount());
        sb.append("（政策 ").append(request.paragraphPolicy()).append("；边界敏感 ")
                .append(request.paragraphBoundarySensitive() ? "是" : "否").append("）");
        if (request.durationSeconds() != null) {
            sb.append("；作答时长 ").append(request.durationSeconds()).append(" 秒");
        }
        sb.append("\n\n## 本次反馈语言\n简体中文\n");
        sb.append("\n## 学生正文\n").append(request.studentText());
        return sb.toString();
    }

    private String userEn(SubjectiveGradeRequest request) {
        StringBuilder sb = new StringBuilder("## Objective metrics\n");
        sb.append("Word count ").append(request.wordCount() == null ? 0 : request.wordCount());
        sb.append("; paragraph count ").append(request.paragraphCount() == null ? 0 : request.paragraphCount());
        sb.append(" (policy ").append(request.paragraphPolicy()).append("; boundary-sensitive ")
                .append(request.paragraphBoundarySensitive() ? "yes" : "no").append(")");
        if (request.durationSeconds() != null) {
            sb.append("; duration ").append(request.durationSeconds()).append(" seconds");
        }
        sb.append("\n\n## Feedback language\nEnglish\n");
        sb.append("\n## Student response\n").append(request.studentText());
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

    private String instructions(SubjectiveGradeRequest request, boolean english) {
        String value = request.instructions();
        if (value == null || value.isBlank()) {
            return english ? "(The assignment instructions were not supplied.)"
                    : "（未提供题目要求，请只依据 rubric 与学生正文判断）";
        }
        return value.strip();
    }

    private static boolean hasAnchors(SubjectiveGradeRequest request) {
        return request.anchors() != null && !request.anchors().isBlank();
    }

    private static boolean hasLengthCriterion(SubjectiveGradeRequest request) {
        return request.criteria().stream()
                .map(SubjectiveGradeRequest.Criterion::name)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains("length") || name.contains("篇幅")
                        || name.contains("字数") || name.contains("段"));
    }

    private static String jsonSkeleton(List<SubjectiveGradeRequest.Criterion> criteria) {
        StringBuilder sb = new StringBuilder("{\"criteria\":[");
        for (int i = 0; i < criteria.size(); i++) {
            SubjectiveGradeRequest.Criterion c = criteria.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(escape(c.name())).append("\",\"points\":0,\"maxPoints\":")
                    .append(points(c.maxPoints())).append(",\"comment\":\"\"}");
        }
        sb.append("],\"total\":0,\"overall\":\"\",\"strength\":\"\",\"suggestion\":\"\"}");
        return sb.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String points(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static boolean isEnglish(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
    }
}
