package com.teachloop.vocab.spelling;

import com.teachloop.vocab.lexicon.FormKey;
import com.teachloop.vocab.lexicon.FormRow;
import com.teachloop.vocab.lexicon.WordFamily;
import com.teachloop.vocab.lexicon.WordFamilyIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 拼写三态校验（T392 / FR-115 / spec §20）。
 *
 * <p>判定顺序：词表精确命中（含去变音符）→ {@code OK}；未命中且词表可用时，
 * **只对未命中词**做一次带上限（编辑距离 ≤2）的全表扫描，取
 * {@code (距离, 频段)} 排序的前 2 个 → {@code SUSPECT}；一个候选都没有 → {@code UNKNOWN}。
 *
 * <p>为什么对 80,579 个短词做暴力扫描（plan §7.1）：BK-tree 在这份数据上剪枝几乎无效，
 * SymSpell 的删除索引要 270 万条变体（100–200MB），而实际只会对少量 miss 词扫描，
 * 1000 行导入通常只有个位数到几十个 miss。词表不可用时直接返回 {@code UNKNOWN} 且不扫描
 * （预览页会说明「未做拼写校验」），绝不误报。
 */
@Component
public class SpellingChecker {

    public enum State {
        OK,
        SUSPECT,
        UNKNOWN
    }

    public record Candidate(String form, int distance, int band) {
    }

    public record SpellingResult(String word, String normalized, State state, Integer band,
                                 List<Candidate> candidates) {
    }

    private static final int MAX_DISTANCE = 2;
    private static final int MAX_CANDIDATES = 2;

    private final WordFamilyIndex index;

    public SpellingChecker(WordFamilyIndex index) {
        this.index = index;
    }

    public boolean available() {
        return index.available();
    }

    public SpellingResult check(String word) {
        String raw = word == null ? "" : word;
        String normalized = FormKey.normalized(raw);
        if (normalized.isEmpty()) {
            return new SpellingResult(raw, normalized, State.UNKNOWN, null, List.of());
        }
        Optional<WordFamily> hit = index.lookup(normalized);
        if (hit.isPresent()) {
            return new SpellingResult(raw, normalized, State.OK, hit.get().band(), List.of());
        }
        if (!index.available()) {
            return new SpellingResult(raw, normalized, State.UNKNOWN, null, List.of());
        }

        List<Candidate> candidates = new ArrayList<>();
        for (FormRow form : index.allForms()) {
            String candidate = form.normalizedForm();
            if (Math.abs(candidate.length() - normalized.length()) > MAX_DISTANCE) {
                continue;
            }
            int distance = boundedDistance(normalized, candidate, MAX_DISTANCE);
            if (distance < 0) {
                continue;
            }
            int band = index.bandOf(form.familyId());
            candidates.add(new Candidate(candidate, distance, band < 0 ? Integer.MAX_VALUE : band));
        }
        candidates.sort(Comparator.comparingInt(Candidate::distance)
                .thenComparingInt(Candidate::band)
                .thenComparing(Candidate::form));
        if (candidates.isEmpty()) {
            return new SpellingResult(raw, normalized, State.UNKNOWN, null, List.of());
        }
        List<Candidate> top = List.copyOf(
                candidates.subList(0, Math.min(MAX_CANDIDATES, candidates.size())));
        return new SpellingResult(raw, normalized, State.SUSPECT, null, top);
    }

    /** 带上限的编辑距离；超过上限或长度差已超上限时返回 -1（不构造整张矩阵）。 */
    static int boundedDistance(String left, String right, int max) {
        int leftLength = left.length();
        int rightLength = right.length();
        if (Math.abs(leftLength - rightLength) > max) {
            return -1;
        }
        int[] previous = new int[rightLength + 1];
        int[] current = new int[rightLength + 1];
        for (int column = 0; column <= rightLength; column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= leftLength; row++) {
            current[0] = row;
            int rowMinimum = current[0];
            for (int column = 1; column <= rightLength; column++) {
                int cost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                int value = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        previous[column - 1] + cost);
                current[column] = value;
                rowMinimum = Math.min(rowMinimum, value);
            }
            if (rowMinimum > max) {
                return -1; // 整行都超上限，后面不可能回到 ≤max
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[rightLength] <= max ? previous[rightLength] : -1;
    }
}
