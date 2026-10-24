package com.teachloop.vocab.spelling;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.lexicon.FamilyRow;
import com.teachloop.vocab.lexicon.FormRow;
import com.teachloop.vocab.lexicon.LexiconData;
import com.teachloop.vocab.lexicon.LexiconManifest;
import com.teachloop.vocab.lexicon.LexiconTestData;
import com.teachloop.vocab.lexicon.WordFamilyIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T392 验收「1000 行导入 &lt;30s」的最坏情况上界：8 万词形 + 1000 个**全部未命中**的词。
 *
 * <p>真实导入里 1000 行通常只有个位数到几十个 miss（plan §7.1），这里故意把 1000 个
 * 都做成 miss，相当于把编辑距离扫描打到满负载；能过，实际导入就更宽裕。
 * 用合成词形而不是真实词表，保证 CI 不依赖 gitignore 的 fixtures-local。
 */
class SpellingThroughputTest {

    @Test
    @DisplayName("8 万词形 + 1000 个全 miss：总耗时 <30s（T392 验收上界）")
    void thousandMissesUnderThirtySeconds() {
        int formCount = 80_000;
        List<FamilyRow> families = new ArrayList<>(formCount);
        List<FormRow> forms = new ArrayList<>(formCount);
        Random random = new Random(42);
        for (int order = 0; order < formCount; order++) {
            long familyId = order + 1L;
            String form = randomWord(random, 7 + random.nextInt(3));
            families.add(new FamilyRow(familyId, form, 1 + (order % 30), 10));
            forms.add(new FormRow(form, form, familyId, 10, true));
        }
        LexiconManifest manifest = new LexiconManifest(
                "teachloop-lexicon-v1", "a".repeat(64),
                new LexiconManifest.Counts(families.size(), forms.size()),
                new LexiconManifest.Artifact("families.tsv", "b".repeat(64), 1, families.size()),
                new LexiconManifest.Artifact("forms.tsv", "c".repeat(64), 1, forms.size()),
                List.of());
        WordFamilyIndex lexicon = LexiconTestData.installedIndex(
                new LexiconData(manifest, families, forms));
        SpellingChecker checker = new SpellingChecker(lexicon);

        List<String> misses = new ArrayList<>(1000);
        for (int index = 0; index < 1000; index++) {
            // 加一个不在合成词表里的字母/数字前缀，保证 1000 个都是 miss
            misses.add("zq" + randomWord(random, 6 + random.nextInt(3)));
        }
        long startedAt = System.nanoTime();
        int suspect = 0;
        for (String miss : misses) {
            if (checker.check(miss).state() == SpellingChecker.State.SUSPECT) {
                suspect++;
            }
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(suspect).isGreaterThan(0);
        assertThat(elapsedMs).as("1000 个全 miss 的扫描耗时（ms）").isLessThan(30_000);
    }

    private static String randomWord(Random random, int length) {
        StringBuilder word = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            word.append((char) ('a' + random.nextInt(26)));
        }
        return word.toString();
    }
}
