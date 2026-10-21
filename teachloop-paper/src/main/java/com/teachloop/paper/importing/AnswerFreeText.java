package com.teachloop.paper.importing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 填空题答案（T236）：答案值不是字母，而是 {@code challenging}、{@code to walk} 这样的文本。
 *
 * <p>选择题的答案必须是选项字母，所以这条通道只对「没有选项的题」开放；判断由调用方按题号
 * 白名单决定，这里只负责「一行里按题号切出若干个文本答案」。</p>
 *
 * <p>规则：第一段题号的分隔符可有可无（{@code 56 challenging} 也认），后续题号必须带分隔符，
 * 避免把答案里的数字（{@code In 1990}）当成新题号；文本尾部「空格 + 左括号」起的说明
 * （{@code (修饰名词 experience)}）直接截掉。</p>
 */
final class AnswerFreeText {

    /** 一条文本答案：题号 + 已经去掉说明的值（值可能为空，由调用方决定怎么报）。 */
    record Entry(int number, String value) {
    }

    /** 行首题号，分隔符可有可无。 */
    private static final Pattern LEADING = Pattern.compile("^(\\d+)\\s*[.、．)）:：]?\\s*");
    /** 行内后续题号，必须有分隔符。 */
    private static final Pattern MARKER = Pattern.compile("(\\d+)\\s*[.、．)）:：]\\s*");

    private AnswerFreeText() {
    }

    /** 整行是不是以题号开头（用来判断这条要不要走文本通道）。 */
    static Integer leadingNumber(String text) {
        Matcher matcher = LEADING.matcher(text.strip());
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /** 把一行按题号切成若干条文本答案；没有题号时返回空列表。 */
    static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<>();
        String line = text.strip();
        Matcher leading = LEADING.matcher(line);
        if (!leading.find()) {
            return out;
        }
        int number = Integer.parseInt(leading.group(1));
        int cursor = leading.end();
        Matcher marker = MARKER.matcher(line);
        while (marker.find(cursor)) {
            if (marker.start() < cursor) {
                continue;
            }
            out.add(new Entry(number, stripComment(line.substring(cursor, marker.start()))));
            number = Integer.parseInt(marker.group(1));
            cursor = marker.end();
        }
        out.add(new Entry(number, stripComment(line.substring(cursor))));
        return out;
    }

    /** 截掉「空格 + 左括号」起的说明。 */
    static String stripComment(String value) {
        String text = value == null ? "" : value.strip();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '(' || c == '（') && Character.isWhitespace(text.charAt(i - 1))) {
                return text.substring(0, i).strip();
            }
        }
        return text;
    }

    /** 只有题号、没写答案的片段返回它的题号，否则返回 {@code null}。 */
    static Integer bareNumber(String text) {
        String t = text == null ? "" : text.strip();
        while (!t.isEmpty() && ",，、/;； ".indexOf(t.charAt(0)) >= 0) {
            t = t.substring(1).strip();
        }
        Matcher matcher = BARE.matcher(t);
        return matcher.matches() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static final Pattern BARE = Pattern.compile("^(\\d+)\\s*[.、．)）]?$");
}
