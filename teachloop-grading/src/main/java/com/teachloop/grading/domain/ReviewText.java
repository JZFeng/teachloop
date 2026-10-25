package com.teachloop.grading.domain;

import java.util.List;

/**
 * 评语文本归一：模型偶尔把字段名当成正文的一部分返回（例如 strength = 「优点：……」）。
 *
 * <p>字段名由界面渲染（复核页的「优点」「建议」、学生反馈的「优点：」「建议：」），
 * 正文里再带一次就会显示成「优点：优点：……」。落库前剥一次，旧数据读取时也剥一次，
 * 这样不用写数据迁移脚本。只有「字段名 + 冒号」才算前缀，普通句子不会被误删。
 */
public final class ReviewText {

    /** 可能出现在正文开头的字段名；必须紧跟冒号（全角或半角）才认定为前缀。 */
    private static final List<String> LABELS = List.of(
            "总评", "评语", "优点", "建议",
            "Overall", "Comment", "Strength", "Strengths", "Suggestion", "Suggestions");

    private ReviewText() {
    }

    /** 剥掉开头重复出现的「优点：」「建议：」这类字段名；null 仍是 null，没有前缀时原样返回。 */
    public static String stripLabel(String value) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String label : LABELS) {
                int end = afterLabel(text, label);
                if (end >= 0) {
                    text = text.substring(end).strip();
                    stripped = true;
                    break;
                }
            }
        }
        return text;
    }

    /** 以 {@code label + 冒号} 开头时返回冒号之后的位置（连续空白已跳过），否则返回 -1。 */
    private static int afterLabel(String text, String label) {
        if (!text.regionMatches(true, 0, label, 0, label.length())) {
            return -1;
        }
        int i = label.length();
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length() || (text.charAt(i) != '：' && text.charAt(i) != ':')) {
            return -1;
        }
        i++;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
