package com.teachloop.grading.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * T072：v1 Python「四模型段落计数」的 Java 移植。
 *
 * <p>原文 algorithm 在 `teaching_english/.../collect_submissions.py`；这里逐条保留：
 * strict_blocks / wraps_merged / policy_default / lenient 四个模型，
 * 以及 greeting 合并、软换行合并、列表块合并、孤立空行合并规则。
 */
public final class TextLengthCounter {

    public static final List<String> MODELS = List.of("strict_blocks", "wraps_merged", "policy_default", "lenient");
    private static final int GREETING_MAX_WORDS = 25;

    private static final Pattern GREETING = Pattern.compile(
            "(?i)^\\s*(hi|hello|hey|good\\s+(morning|afternoon|evening)|dear)\\b");
    private static final Pattern SELF_INTRO = Pattern.compile(
            "(?i)^\\s*(my\\s+name\\s+is|i\\s+am\\s+[A-Z]|i'm\\s+[A-Z]|this\\s+is\\s+[A-Z])");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*([-*\u2022]|\\(?[a-zA-Z0-9]{1,3}[.)])\\s+\\S");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?\"'\u201d\u2019)\\]]\\s*$");

    private TextLengthCounter() {
    }

    public static String normalize(String raw) {
        return raw.replace("\r\n", "\n").replace("\r", "\n").replace("\u00a0", " ");
    }

    public static List<String> splitBlocks(String text) {
        List<String> out = new ArrayList<>();
        for (String block : normalize(text).split("\n[ \t]*\n", -1)) {
            String trimmed = block.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    public static int count(String text, String model) {
        return countModel(text, model).size();
    }

    public static Map<String, Integer> countsByModel(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String model : MODELS) {
            out.put(model, count(text, model));
        }
        return out;
    }

    /** 与 v1 的 boundary_sensitive 对齐：四个模型里是否出现不同的是/否判断。 */
    public static boolean boundarySensitive(String text, int min, int max) {
        boolean any = false;
        boolean all = true;
        for (int n : countsByModel(text).values()) {
            boolean inRange = n >= min && n <= max;
            any |= inRange;
            all &= inRange;
        }
        return any && !all;
    }

    private static List<String> countModel(String text, String model) {
        boolean mergeWraps = !"strict_blocks".equals(model);
        boolean mergeGreeting = "policy_default".equals(model) || "lenient".equals(model);
        boolean coalesceLists = "policy_default".equals(model) || "lenient".equals(model);
        boolean mergeContinuation = "lenient".equals(model);
        boolean listBlockAsOne = !"strict_blocks".equals(model);

        List<String> blocks = splitBlocks(text);
        List<String> paragraphs = new ArrayList<>();
        List<String> pendingList = new ArrayList<>();
        for (String block : blocks) {
            if (isListBlock(block)) {
                if (listBlockAsOne) {
                    pendingList.add(mergeListBlock(block));
                } else {
                    for (String line : block.split("\n")) {
                        if (!line.strip().isEmpty()) {
                            pendingList.add(line.strip());
                        }
                    }
                }
                continue;
            }
            flushList(paragraphs, pendingList, coalesceLists);
            String body = mergeWraps ? mergeHardWraps(block) : block;
            for (String piece : body.split("\n")) {
                String trimmed = piece.strip();
                if (!trimmed.isEmpty()) {
                    paragraphs.add(trimmed);
                }
            }
        }
        flushList(paragraphs, pendingList, coalesceLists);

        if (mergeContinuation) {
            List<String> merged = new ArrayList<>();
            for (String piece : paragraphs) {
                if (!merged.isEmpty() && continuation(merged.get(merged.size() - 1), piece)) {
                    merged.set(merged.size() - 1, merged.get(merged.size() - 1) + " " + piece);
                } else {
                    merged.add(piece);
                }
            }
            paragraphs = merged;
        }
        if (mergeGreeting && paragraphs.size() > 1 && isGreetingBlock(paragraphs.get(0))) {
            paragraphs.set(1, (paragraphs.get(0) + " " + paragraphs.get(1)).strip());
            paragraphs.remove(0);
        }
        return paragraphs;
    }

    private static void flushList(List<String> paragraphs, List<String> pending, boolean coalesce) {
        if (pending.isEmpty()) {
            return;
        }
        if (coalesce) {
            paragraphs.add(String.join(" ", pending));
        } else {
            paragraphs.addAll(pending);
        }
        pending.clear();
    }

    private static boolean isGreetingBlock(String block) {
        String stripped = block.strip();
        boolean singleLine = !stripped.contains("\n");
        int words = stripped.isEmpty() ? 0 : stripped.split("\\s+").length;
        if (!singleLine || words > GREETING_MAX_WORDS) {
            return false;
        }
        return GREETING.matcher(stripped).find() || SELF_INTRO.matcher(stripped).find();
    }

    private static String mergeListBlock(String block) {
        List<String> lines = new ArrayList<>();
        for (String line : block.split("\n")) {
            if (!line.strip().isEmpty()) {
                lines.add(line.strip());
            }
        }
        return String.join(" ", lines);
    }

    private static boolean isListBlock(String block) {
        List<String> lines = new ArrayList<>();
        for (String line : block.split("\n")) {
            if (!line.strip().isEmpty()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return false;
        }
        int hits = 0;
        for (String line : lines) {
            if (LIST_ITEM.matcher(line).find()) {
                hits++;
            }
        }
        if (hits == 0) {
            return false;
        }
        if (lines.size() == 1) {
            return true;
        }
        return hits >= Math.max(2, lines.size() / 2);
    }

    private static String mergeHardWraps(String block) {
        List<String> lines = new ArrayList<>();
        for (String line : block.split("\n")) {
            if (!line.strip().isEmpty()) {
                lines.add(line.strip());
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        out.add(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String prev = out.get(out.size() - 1);
            boolean hardBreak = SENTENCE_END.matcher(prev).find()
                    && (firstIsUpper(line) || "\u201c\"'()[]".indexOf(firstChar(line)) >= 0);
            if (hardBreak) {
                out.add(line);
            } else {
                out.set(out.size() - 1, prev + " " + line);
            }
        }
        return String.join("\n", out);
    }

    private static boolean continuation(String prev, String next) {
        if (prev.isEmpty() || next.isEmpty() || SENTENCE_END.matcher(prev).find()) {
            return false;
        }
        char first = firstChar(next.strip());
        return Character.isLowerCase(first) || "\u201c\"'([".indexOf(first) >= 0;
    }

    private static char firstChar(String s) {
        return s.isEmpty() ? '\0' : s.charAt(0);
    }

    private static boolean firstIsUpper(String s) {
        char c = firstChar(s);
        return Character.isUpperCase(c);
    }
}
