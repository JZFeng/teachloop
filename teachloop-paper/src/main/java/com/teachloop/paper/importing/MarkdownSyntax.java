package com.teachloop.paper.importing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Markdown 的排版符号去掉，留下「试卷本身的内容」（T231）。
 *
 * <p>真实试卷转成 .md 之后，题号行往往带着 Markdown 装饰：
 * {@code ## 第一部分 听力}、{@code **A**}、{@code - 4-week program: $1300}、
 * {@code | 5-week program | June 14 | $1600 |}。解析器只认顶格纯文本，
 * 所以先在这里把装饰剥掉，而不是让解析器去兼容几十种 Markdown 写法。</p>
 *
 * <p>刻意不动下划线：{@code ______} 是填空题的空，剥掉就没题了。</p>
 */
final class MarkdownSyntax {

    /** 分隔线：{@code ---} / {@code ***} / {@code ___} / {@code ===}（3 个以上）。 */
    private static final Pattern RULE = Pattern.compile("^\\s*(?:[-*_=]\\s*){3,}$");
    /** 标题：{@code #} ~ {@code ######}。 */
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s*");
    /** 引用块：{@code > 文字}。 */
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?");
    /** 无序列表：{@code - 文字} / {@code * 文字} / {@code + 文字}。 */
    private static final Pattern BULLET = Pattern.compile("^\\s{0,3}[-*+]\\s+");
    /** 表格行：{@code | a | b |}。 */
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    /** 表格分隔行里的单元格：{@code ---} / {@code :---:}。 */
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile("^:?-{1,}:?$");

    private MarkdownSyntax() {
    }

    /** 返回剥掉 Markdown 装饰后的行；行号沿用原始行号，报错时仍指向原文。 */
    static List<DocumentTextExtractor.SourceLine> clean(List<DocumentTextExtractor.SourceLine> lines) {
        List<DocumentTextExtractor.SourceLine> out = new ArrayList<>(lines.size());
        for (DocumentTextExtractor.SourceLine line : lines) {
            String text = line.text() == null ? "" : line.text();
            if (RULE.matcher(text).matches()) {
                continue;
            }
            text = HEADING.matcher(text).replaceFirst("");
            text = QUOTE.matcher(text).replaceFirst("");
            text = tableRow(text);
            if (text == null) {
                continue;
            }
            text = BULLET.matcher(text).replaceFirst("");
            // 加粗/斜体/行内代码的标记本身没有语义，去掉；** 先于 *
            text = text.replace("**", "").replace("`", "");
            // 空行保留：行号要跟原文对得上，解析器自己会跳过空行
            out.add(new DocumentTextExtractor.SourceLine(line.number(), text));
        }
        return out;
    }

    /**
     * 表格行压成「单元格 | 单元格」一行；分隔行返回 {@code null}（整行丢弃）。
     * 空单元格丢掉，避免出现 {@code Dates |  | Costs} 这种空洞。
     */
    private static String tableRow(String text) {
        if (!TABLE_ROW.matcher(text).matches()) {
            return text;
        }
        String inner = text.strip();
        inner = inner.substring(1, inner.length() - 1);
        String[] cells = inner.split("\\|", -1);
        List<String> kept = new ArrayList<>(cells.length);
        for (String cell : cells) {
            String value = cell.strip();
            if (value.isEmpty()) {
                continue;
            }
            kept.add(value);
        }
        if (kept.isEmpty()) {
            return null;
        }
        boolean separator = kept.stream().allMatch(value -> TABLE_SEPARATOR_CELL.matcher(value).matches());
        if (separator) {
            return null;
        }
        return String.join(" | ", kept);
    }
}
