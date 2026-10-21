package com.teachloop.sitting.service;

import com.teachloop.shared.port.Ports;
import com.teachloop.shared.text.BlankText;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>任何一项校验不过（空数对不上、题号标签顺序不对、选项形态不一致、多选/主观题），
 * 整组退回 {@code SOLO}，宁可重复也不把题拆错。</p>
 */
public final class MaterialGrouper {

    /** 题干里的空：3 个及以上下划线。 */
    private static final Pattern BLANK = Pattern.compile("_{3,}");
    /** 导入时为每个空标的题号：{@code ___(41)___}。 */
    private static final Pattern LABELED = Pattern.compile("___\\((\\d+)\\)___");
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
        List<Candidate> candidates = items.stream().map(MaterialGrouper::classify).toList();
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

    private static Candidate classify(Ports.ItemSpec item) {
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
                return new Candidate(item, Kind.READING, normalize(material), question, -1);
            }
        }

        // 完形 / 语法填空：整段材料，空上有题号标签；标签前的空数就是这个空的序号。
        Matcher labeled = LABELED.matcher(stem);
        if (labeled.find() && labeled.group(1).equals(String.valueOf(item.number()))) {
            int blankIndex = blankCount(stem.substring(0, labeled.start()));
            String material = normalize(stem);
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
        if (blankCount(first.material()) != group.size()) {
            return false;
        }
        boolean choice = hasOptions(first.item());
        for (int i = 0; i < group.size(); i++) {
            Candidate candidate = group.get(i);
            if (candidate.blankIndex() != i || hasOptions(candidate.item()) != choice) {
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

    /** 共用材料的文本口径：空位由内联控件渲染，标记与重复题号都收掉（见 {@link BlankText#forGrouped}）。 */
    private static String normalize(String text) {
        return BlankText.forGrouped(text);
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
