package com.teachloop.sitting.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 作业 rubric 解析用的中英文词表（T258）。
 *
 * <p>这些是**文档结构标记**，不是界面文案：中文老师会把「评分标准」「总分」「是/否」
 * 写在 rubric 原文里，解析器必须认。放在 domain 而不是 service，避免与
 * `scripts/check-i18n.py` 的「api/service 不得新增中文字面量」规则混淆——
 * 那条规则管的是返回给用户的文案，这些词只用于匹配输入。</p>
 */
public final class RubricVocabulary {

    /** 「评分项」或「2 分 / 2 points」。 */
    public static final Pattern MAX_POINTS =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:分|points?|pts?)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> START = Set.of(
            "评分标准", "评分项", "scoring rubric", "rubric", "grading rubric");
    private static final Set<String> END = Set.of(
            "maximum score", "total score", "总分", "满分", "optional feedback", "反馈");
    private static final Set<String> IGNORED = Set.of(
            "assignment", "statement", "作业", "作业说明", "overall assignment rubric",
            "评分标准", "scoring rubric", "rubric", "maximum score", "total score",
            "总分", "满分", "optional feedback", "反馈", "grading rubric");

    private RubricVocabulary() {
    }

    /** 进入评分项区域的标题。{@code name} 必须已转小写。 */
    public static boolean isStart(String name) {
        return name != null && START.contains(name);
    }

    /** 结束评分项区域的标题。{@code name} 必须已转小写。 */
    public static boolean isEnd(String name) {
        return name != null && (END.contains(name) || name.startsWith("maximum score"));
    }

    /** 要忽略的标题/条目。{@code name} 必须已转小写。 */
    public static boolean isIgnored(String name) {
        return name != null && IGNORED.contains(name);
    }

    /** Yes/No、是/否、部分/完整、无这类判断行，不是评分项。 */
    public static boolean isDecision(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT).strip();
        return lower.equals("yes") || lower.equals("no") || lower.equals("true") || lower.equals("false")
                || lower.startsWith("yes ") || lower.startsWith("no ")
                || lower.startsWith("是") || lower.startsWith("否")
                || lower.startsWith("partial") || lower.startsWith("部分")
                || lower.startsWith("full") || lower.startsWith("完整")
                || lower.startsWith("none") || lower.startsWith("无");
    }
}
