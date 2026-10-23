package com.teachloop.sitting.service;

import com.teachloop.shared.port.Ports;
import com.teachloop.shared.text.BlankText;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 学生答题页的「共用材料」分组（T321）。
 *
 * <p>导入时材料被并入每道题的题干（AC-4.16 / D-12），所以快照里同一篇文章会出现多次。
 * 这里只做**展示用的分组**，不改快照、不改答案与分值：把连续的、材料完全相同的题标成同一组，
 * 并给出材料、题目的局部题干、空位序号。</p>
 *
 * <p>三种分组：</p>
 * <ul>
 *   <li>{@code READING}：题干形如「材料 + 空行 + 问题」，材料只渲染一次，题目逐条渲染；</li>
 *   <li>{@code BLANK}：整段就是材料，材料里带多个空，第 k 个空对应组内第 k 题；</li>
 *   <li>{@code SOLO}：无法可靠拆分，保持现状（每道题自己的题干）。</li>
 * </ul>
 *
 * <p>空数与题数不要求相等：材料里有 5 个空、只有第 1、2 个空是本题组（如七选五只导入 2 题），
 * 仍然只渲染一次材料，控件落在各自空位，其余空位保持普通横线（T355）。空位序号必须严格递增、
 * 不重复、且都落在材料空数范围内。</p>
 *
 * <p>任何一项校验不过（空位序号对不上、选项形态不一致、多选/主观题），整组退回 {@code SOLO}，
 * 宁可重复也不把题拆错。</p>
 */
public final class MaterialGrouper {

    /** 题干里的空：3 个及以上下划线。 */
    private static final Pattern BLANK = Pattern.compile("_{3,}");
    /** 导入时为每个空标的题号：{@code ___(41)___}。 */
    private static final Pattern LABELED = Pattern.compile("___\\((\\d+)\\)___");
    /** 别的题的空位题号提示：{@code ______ (9)}。同一材料在每道题的题干里都存了一份，提示口径不一致就分不了组。 */
    private static final Pattern BLANK_HINT = Pattern.compile("(_{3,})\\s*[(（](\\d+)[)）]");
    /** 材料至少这么长才认定是篇章，避免把短题干当材料。 */
    private static final int MIN_MATERIAL = 80;
    /** 题目最多这么长；更长的后半段更可能是材料正文而非题干。 */
    private static final int MAX_QUESTION = 600;

    private MaterialGrouper() {
    }

    /** 一道题的分组结果；{@code groupKey} 为空表示不分组。 */
    public record GroupedItem(Ports.ItemSpec item, String groupKey, String material,
                              String questionStem, Integer blankIndex) {
    }

    private enum Kind { READING, BLANK, SOLO }

    private record Candidate(Ports.ItemSpec item, Kind kind, String material, String question, int blankIndex) {
    }

    public static List<GroupedItem> group(List<Ports.ItemSpec> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Set<Integer> numbers = items.stream().map(Ports.ItemSpec::number).collect(Collectors.toSet());
        List<Candidate> candidates = items.stream().map(item -> classify(item, numbers)).toList();
        List<GroupedItem> out = new ArrayList<>();
        int index = 0;
        while (index < candidates.size()) {
            Candidate first = candidates.get(index);
            int end = index + 1;
            if (first.kind() != Kind.SOLO) {
                while (end < candidates.size()
                        && candidates.get(end).kind() == first.kind()
                        && candidates.get(end).item().sectionPosition() == first.item().sectionPosition()
                        && candidates.get(end).material().equals(first.material())) {
                    end++;
                }
            }
            List<Candidate> group = candidates.subList(index, end);
            if (first.kind() == Kind.SOLO || group.size() < 2) {
                for (Candidate candidate : group) {
                    out.add(solo(candidate.item()));
                }
            } else if (first.kind() == Kind.READING) {
                addReading(group, out);
            } else if (validBlankGroup(group)) {
                addBlank(group, out);
            } else {
                for (Candidate candidate : group) {
                    out.add(solo(candidate.item()));
                }
            }
            index = end;
        }
        return out;
    }

    private static void addReading(List<Candidate> group, List<GroupedItem> out) {
        String key = "g" + group.get(0).item().number();
        for (int i = 0; i < group.size(); i++) {
            Candidate candidate = group.get(i);
            out.add(new GroupedItem(candidate.item(), key, i == 0 ? candidate.material() : null,
                    candidate.question(), null));
        }
    }

    private static void addBlank(List<Candidate> group, List<GroupedItem> out) {
        String key = "g" + group.get(0).item().number();
        for (int i = 0; i < group.size(); i++) {
            Candidate candidate = group.get(i);
            out.add(new GroupedItem(candidate.item(), key, i == 0 ? candidate.material() : null,
                    null, candidate.blankIndex()));
        }
    }

    /**
     * 不分组：题干整段直接展示。这里必须洗掉内部空标记，否则 {@code ___(9)___ (9)} 会漏到界面。
     */
    private static GroupedItem solo(Ports.ItemSpec item) {
        return new GroupedItem(item, null, null, BlankText.forText(item.stem()), null);
    }

    private static Candidate classify(Ports.ItemSpec item, Set<Integer> numbers) {
        String stem = item.stem();
        if (stem == null || stem.isBlank()) {
            return soloCandidate(item);
        }

        // 阅读：材料 + 空行 + 问题。材料里不能带空，否则更像完形/语法填空。
        int split = stem.lastIndexOf("\n\n");
        if (split > 0) {
            String material = stem.substring(0, split).strip();
            String question = stem.substring(split + 2).strip();
            if (material.length() >= MIN_MATERIAL
                    && !question.isEmpty()
                    && question.length() <= MAX_QUESTION
                    && blankCount(material) == 0) {
                return new Candidate(item, Kind.READING, normalize(material, numbers), question, -1);
            }
        }

        // 完形 / 语法填空：整段材料，空上有题号标签；标签前的空数就是这个空的序号。
        Matcher labeled = LABELED.matcher(stem);
        if (labeled.find() && labeled.group(1).equals(String.valueOf(item.number()))) {
            int blankIndex = blankCount(stem.substring(0, labeled.start()));
            String material = normalize(stem, numbers);
            if (blankCount(material) >= 2) {
                return new Candidate(item, Kind.BLANK, material, null, blankIndex);
            }
        }
        return soloCandidate(item);
    }

    private static Candidate soloCandidate(Ports.ItemSpec item) {
        return new Candidate(item, Kind.SOLO, "", null, -1);
    }

    private static boolean validBlankGroup(List<Candidate> group) {
        Candidate first = group.get(0);
        int blanks = blankCount(first.material());
        // 材料里可以有比题组更多的空（其他空没有对应题目），但一个空不能放两道题。
        if (blanks < group.size()) {
            return false;
        }
        boolean choice = hasOptions(first.item());
        int previous = -1;
        for (Candidate candidate : group) {
            int index = candidate.blankIndex();
            if (index < 0 || index >= blanks || index <= previous) {
                return false;
            }
            previous = index;
            if (hasOptions(candidate.item()) != choice) {
                return false;
            }
            String type = candidate.item().questionType();
            if ("MULTIPLE_CHOICE".equals(type) || "ESSAY".equals(type)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasOptions(Ports.ItemSpec item) {
        return item.options() != null && !item.options().isBlank();
    }

    /**
     * 共用材料的文本口径：空位由内联控件渲染，标记与重复题号都收掉（见 {@link BlankText#forGrouped}）。
     *
     * <p>同一段材料在每道题的题干里各存一份，导入器只给「本题自己的空」打标记，别的空保留卷面提示
     * {@code ______ (9)}。这些提示里的号是别的题的题号、文字也各不相同，不洗掉两组材料就不相等、
     * 分不了组。只洗「本卷真实存在的题号」，{@code ______ (2019)} 这类正文括号不动。</p>
     */
    private static String normalize(String text, Set<Integer> numbers) {
        return stripBlankHints(BlankText.forGrouped(text), numbers);
    }

    private static String stripBlankHints(String text, Set<Integer> numbers) {
        Matcher matcher = BLANK_HINT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            // 括号里可能是一串很长的数字（年份、价格），解析不了就原样保留，绝不因为题干内容抛异常。
            Integer hint = parseNumber(matcher.group(2));
            String replacement = hint != null && numbers.contains(hint)
                    ? matcher.group(1) : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Integer parseNumber(String digits) {
        if (digits == null || digits.isEmpty() || digits.length() > 6) {
            return null;
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int blankCount(String text) {
        Matcher matcher = BLANK.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
