package com.teachloop.paper.importing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T055 / T236：从粘贴的答案表生成/补齐标准答案。
 *
 * <p>支持「1 A」「1A」「1 A」「1-5 ABCDA」「1-5: A B B C C」「1 A 2 B 3 C」；
 * 粘贴进来的内容先过一遍 {@link MarkdownSyntax}，所以 {@code ---}、{@code #}、{@code **}、
 * 列表与表格记号都不会再把整份答案表打挂。</p>
 *
 * <p>填空题的答案不是字母，走 {@code freeTextNumbers} 白名单：命中的题号整行按题号切成若干条
 * 文本答案（{@code 56. challenging}、{@code 66. to walk  67. impressive}）。不在白名单里的
 * 题号仍然只认字母，写错会明确报错而不是默默存成字母。</p>
 */
public final class AnswerTableParser {

    public record Entry(int number, int line, String answer) {
    }

    public record Result(List<Entry> entries, List<String> warnings) {
    }

    /** 题号 + 可选区间 + 可选分隔符 + 字母；字母两侧要求「不是字母」的边界。 */
    private static final Pattern PAIR = Pattern.compile(
            "(\\d+)\\s*(?:[-–—~～至]\\s*(\\d+))?\\s*[.、．)）:：]?\\s*"
                    + "(?<![A-Za-z])([A-Ga-g]+(?:[\\s,，、/]+[A-Ga-g]+)*)(?![A-Za-z])");

    private AnswerTableParser() {
    }

    public static Result parse(String text) {
        return parse(text, Set.of());
    }

    /**
     * @param freeTextNumbers 填空题题号：这些题号整行当作文本答案，不按字母解析
     */
    public static Result parse(String text, Set<Integer> freeTextNumbers) {
        if (text == null || text.isBlank()) {
            throw new ImportException("答案表是空的");
        }
        List<DocumentTextExtractor.SourceLine> lines = new ArrayList<>();
        String[] rows = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < rows.length; i++) {
            lines.add(new DocumentTextExtractor.SourceLine(i + 1, rows[i]));
        }
        return parse(MarkdownSyntax.clean(lines), freeTextNumbers);
    }

    static Result parse(List<DocumentTextExtractor.SourceLine> lines, Set<Integer> freeTextNumbers) {
        List<Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<Integer, Integer> seen = new LinkedHashMap<>();
        DocumentTextExtractor.SourceLine skipped = null;
        for (DocumentTextExtractor.SourceLine source : lines) {
            String line = source.text() == null ? "" : source.text().trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Integer leading = AnswerFreeText.leadingNumber(line);
            if (leading != null && freeTextNumbers.contains(leading)) {
                for (AnswerFreeText.Entry entry : AnswerFreeText.parse(line)) {
                    if (entry.value().isEmpty()) {
                        warnings.add("第 " + source.number() + " 行：题号 " + entry.number() + " 没有答案，已跳过");
                    } else {
                        add(entries, seen, warnings, entry.number(), entry.value(), source.number());
                    }
                }
                continue;
            }
            Matcher matcher = PAIR.matcher(line);
            boolean any = false;
            while (matcher.find()) {
                any = true;
                int from = Integer.parseInt(matcher.group(1));
                int to = matcher.group(2) == null ? from : Integer.parseInt(matcher.group(2));
                String letters = normalizeLetters(matcher.group(3));
                if (to < from) {
                    throw new ImportException("第 " + source.number() + " 行：题号区间 " + from + "-" + to + " 不合法");
                }
                int count = to - from + 1;
                if (count != letters.length()) {
                    throw new ImportException("第 " + source.number() + " 行：题号区间 " + from + "-" + to
                            + " 有 " + count + " 题，但答案有 " + letters.length() + " 个（" + letters + "）");
                }
                for (int n = from; n <= to; n++) {
                    add(entries, seen, warnings, n, String.valueOf(letters.charAt(n - from)), source.number());
                }
            }
            if (!any) {
                if (AnswerFreeText.leadingNumber(line) != null) {
                    throw new ImportException("第 " + source.number()
                            + " 行：无法识别为答案，应写成「1 A」或「1-5 ABCDA」");
                }
                // 答案表里的标题、说明、范文：跳过，不让它们打挂整份答案
                if (skipped == null) {
                    skipped = source;
                }
                continue;
            }
        }
        if (entries.isEmpty()) {
            if (skipped != null) {
                throw new ImportException("第 " + skipped.number()
                        + " 行：无法识别为答案，应写成「1 A」或「1-5 ABCDA」");
            }
            throw new ImportException("答案表里没有可用的题号");
        }
        return new Result(entries, warnings);
    }

    private static void add(List<Entry> entries, Map<Integer, Integer> seen, List<String> warnings,
                            int number, String answer, int line) {
        if (seen.putIfAbsent(number, line) != null) {
            warnings.add("第 " + line + " 行：题号 " + number + " 重复，已保留首次答案");
            return;
        }
        entries.add(new Entry(number, line, answer));
    }

    /** 只保留 A–G 字母并统一大写。 */
    private static String normalizeLetters(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toUpperCase(raw.charAt(i));
            if (c >= 'A' && c <= 'G') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
