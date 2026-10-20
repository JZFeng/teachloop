package com.teachloop.paper.importing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T055：从粘贴的答案表生成/补齐客观题答案。
 * 支持「1 A」「1-5 ABCDA」「1 A 2 B 3 C」；解析失败带行号。
 */
public final class AnswerTableParser {

    public record Entry(int number, int line, String answer) {
    }

    public record Result(List<Entry> entries, List<String> warnings) {
    }

    private static final Pattern PAIR = Pattern.compile(
            "(\\d+)\\s*(?:[-–—~～至]\\s*(\\d+))?\\s*[.、．)）:：]?\\s*([A-Ga-g]+)");

    private AnswerTableParser() {
    }

    public static Result parse(String text) {
        if (text == null || text.isBlank()) {
            throw new ImportException("答案表是空的");
        }
        List<Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<Integer, Integer> seen = new LinkedHashMap<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher m = PAIR.matcher(line);
            boolean any = false;
            while (m.find()) {
                any = true;
                int from = Integer.parseInt(m.group(1));
                int to = m.group(2) == null ? from : Integer.parseInt(m.group(2));
                String letters = m.group(3).toUpperCase();
                if (to < from) {
                    throw new ImportException("第 " + (i + 1) + " 行：题号区间 " + from + "-" + to + " 不合法");
                }
                int count = to - from + 1;
                if (count != letters.length()) {
                    throw new ImportException("第 " + (i + 1) + " 行：题号区间有 " + count + " 题，但答案有 "
                            + letters.length() + " 个");
                }
                for (int n = from; n <= to; n++) {
                    String answer = String.valueOf(letters.charAt(n - from));
                    if (seen.putIfAbsent(n, i + 1) != null) {
                        warnings.add("第 " + (i + 1) + " 行：题号 " + n + " 重复，已保留首次答案");
                        continue;
                    }
                    entries.add(new Entry(n, i + 1, answer));
                }
            }
            if (!any) {
                throw new ImportException("第 " + (i + 1) + " 行：无法识别为答案，应写成「1 A」或「1-5 ABCDA」");
            }
        }
        if (entries.isEmpty()) {
            throw new ImportException("答案表里没有可用的题号");
        }
        return new Result(entries, warnings);
    }
}
