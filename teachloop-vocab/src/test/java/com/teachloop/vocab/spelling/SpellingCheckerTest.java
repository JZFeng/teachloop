package com.teachloop.vocab.spelling;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.lexicon.LexiconTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T392：三态判定、候选排序（距离, 频段）与词表不可用时的降级。 */
class SpellingCheckerTest {

    private final SpellingChecker checker = new SpellingChecker(LexiconTestData.installedIndex());

    @Test
    @DisplayName("OK：词表命中，带频段")
    void ok() {
        SpellingChecker.SpellingResult result = checker.check("Apple");
        assertThat(result.state()).isEqualTo(SpellingChecker.State.OK);
        assertThat(result.normalized()).isEqualTo("apple");
        assertThat(result.band()).isEqualTo(1);
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    @DisplayName("SUSPECT：cleever → clever")
    void cleeverSuggestClever() {
        SpellingChecker.SpellingResult result = checker.check("cleever");
        assertThat(result.state()).isEqualTo(SpellingChecker.State.SUSPECT);
        assertThat(result.candidates()).extracting(SpellingChecker.Candidate::form).contains("clever");
    }

    @Test
    @DisplayName("SUSPECT：acommodate → accommodate")
    void acommodateSuggestAccommodate() {
        SpellingChecker.SpellingResult result = checker.check("acommodate");
        assertThat(result.state()).isEqualTo(SpellingChecker.State.SUSPECT);
        assertThat(result.candidates()).extracting(SpellingChecker.Candidate::form)
                .contains("accommodate");
    }

    @Test
    @DisplayName("SUSPECT：recieve 距离 2，但距离 1 的 relieve 在前、receive 仍进前 2")
    void recieveKeepsReceiveInTopTwo() {
        SpellingChecker.SpellingResult result = checker.check("recieve");
        assertThat(result.state()).isEqualTo(SpellingChecker.State.SUSPECT);
        assertThat(result.candidates()).extracting(SpellingChecker.Candidate::form)
                .containsExactly("relieve", "receive");
        assertThat(result.candidates()).extracting(SpellingChecker.Candidate::distance)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("候选按 (距离, 频段) 排序；同距离时低频段在前")
    void candidatesSortedByDistanceThenBand() {
        SpellingChecker.SpellingResult result = checker.check("acommodates");
        assertThat(result.candidates()).isSortedAccordingTo(
                java.util.Comparator.comparingInt(SpellingChecker.Candidate::distance)
                        .thenComparingInt(SpellingChecker.Candidate::band));
    }

    @Test
    @DisplayName("UNKNOWN：zyzzyva 没有 ≤2 的候选，不编造")
    void unknownWord() {
        SpellingChecker.SpellingResult result = checker.check("zyzzyva");
        assertThat(result.state()).isEqualTo(SpellingChecker.State.UNKNOWN);
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    @DisplayName("词表不可用：不扫描、不误报，三态降级为 UNKNOWN")
    void unavailableIndexDegrades() {
        SpellingChecker off = new SpellingChecker(LexiconTestData.unavailableIndex());
        assertThat(off.available()).isFalse();
        assertThat(off.check("recieve").state()).isEqualTo(SpellingChecker.State.UNKNOWN);
        assertThat(off.check("recieve").candidates()).isEmpty();
    }

    @Test
    @DisplayName("带上限编辑距离：≤2 正确、>2 返回 -1、长度差超限直接 -1")
    void boundedDistance() {
        assertThat(SpellingChecker.boundedDistance("clever", "cleever", 2)).isEqualTo(1);
        assertThat(SpellingChecker.boundedDistance("acommodate", "accommodate", 2)).isEqualTo(1);
        assertThat(SpellingChecker.boundedDistance("recieve", "receive", 2)).isEqualTo(2);
        assertThat(SpellingChecker.boundedDistance("kitten", "sitting", 2)).isEqualTo(-1);
        assertThat(SpellingChecker.boundedDistance("a", "abcd", 2)).isEqualTo(-1);
    }
}
