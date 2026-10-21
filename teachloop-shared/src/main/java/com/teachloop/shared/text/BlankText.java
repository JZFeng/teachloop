package com.teachloop.shared.text;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题干里「空」的统一文本口径。
 *
 * <p>导入器给第 k 个空打内部标记 {@code ___(题号)___}（{@code MaterialGrouper} 靠它切分材料）。
 * 原始卷面通常还会自带一个题号，如 {@code ______ (7)}；标记替换下划线后就成了
 * {@code ___(7)___ (7)}。这层内部约定绝不能直接漏给学生，所以所有「把题干交给界面 / 交给模型 /
 * 交给导出件」的地方都要先过这里。</p>
 *
 * <p>两个口径，别混用：</p>
 * <ul>
 *   <li>{@link #forText}：题干正文。标记还原成 {@code ______ (题号)}，句子读得通、也能看出这是第几空。</li>
 *   <li>{@link #forGrouped}：答题页共用材料。空位由内联控件渲染、题号由控件显示，文本里只留
 *       {@code ______}，否则会「控件 9 + 文本 (9)」双写。</li>
 * </ul>
 *
 * <p>两个方法都幂等：已经干净的文本再洗一遍不变。</p>
 */
public final class BlankText {

    /** 标记 + 紧跟一个同号括号：{@code ___(7)___ (7)}，导入修复前的老快照长这样。 */
    private static final Pattern LABELED_THEN_SAME_NUMBER =
            Pattern.compile("___\\((\\d+)\\)___\\s*[(（]\\s*(\\d+)\\s*[)）]");
    /** 括号在标记前面：{@code (7) ___(7)___}。 */
    private static final Pattern SAME_NUMBER_THEN_LABELED =
            Pattern.compile("[(（]\\s*(\\d+)\\s*[)）]\\s*___\\((\\d+)\\)___");
    /** 只有标记：{@code ___(7)___}。 */
    private static final Pattern LABELED = Pattern.compile("___\\((\\d+)\\)___");
    /** 连续 3 个及以上下划线。 */
    private static final Pattern UNDERSCORES = Pattern.compile("_{3,}");

    private BlankText() {
    }

    /** 题干正文口径：{@code ___(9)___ (9)} → {@code ______ (9)}；重复的括号题号被吸收。 */
    public static String forText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 括号在前的同号重复：(9) ___(9)___ → ______ (9)
        String out = replaceWhenEqual(SAME_NUMBER_THEN_LABELED, text,
                n -> "______ (" + n + ")");
        // 括号在后的同号重复：___(9)___ (9) → ______ (9)
        out = replaceWhenEqual(LABELED_THEN_SAME_NUMBER, out,
                n -> "______ (" + n + ")");
        // 剩下的裸标记：___(9)___ → ______ (9)
        out = LABELED.matcher(out).replaceAll("______ ($1)");
        return UNDERSCORES.matcher(out).replaceAll("______");
    }

    /** 共用材料口径：{@code ___(9)___ (9)} → {@code ______}；题号交给内联控件显示。 */
    public static String forGrouped(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = replaceWhenEqual(SAME_NUMBER_THEN_LABELED, text, n -> "______");
        out = replaceWhenEqual(LABELED_THEN_SAME_NUMBER, out, n -> "______");
        out = LABELED.matcher(out).replaceAll("______");
        return UNDERSCORES.matcher(out).replaceAll("______");
    }

    /**
     * 两个捕获组都是题号且相等时才替换；不相等说明括号里是正文（如 {@code (2019)}），原样保留。
     *
     * @param replacer 入参是第一个捕获组（题号），返回替换文本
     */
    private static String replaceWhenEqual(Pattern pattern, String text,
                                           Function<String, String> replacer) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String first = matcher.group(1);
            String second = matcher.group(2);
            String replacement = first.equals(second) ? replacer.apply(first) : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
