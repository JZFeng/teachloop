package com.teachloop.sitting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObjectiveScoringTest {

    private static Ports.ItemSpec item(String type, String key, BigDecimal points, String strategy) {
        return new Ports.ItemSpec(UUID.randomUUID(), 1, type, key, points, "stem", null, 0, null, strategy);
    }

    @Test
    void singleChoiceIsCaseInsensitive() {
        var spec = item("SINGLE_CHOICE", "A", new BigDecimal("10"), "ALL_OR_NOTHING");
        assertThat(ObjectiveScoring.award(spec, "a")).isEqualByComparingTo("10");
        assertThat(ObjectiveScoring.award(spec, "B")).isEqualByComparingTo("0");
        assertThat(ObjectiveScoring.award(spec, "")).isEqualByComparingTo("0");
    }

    @Test
    void fillBlankTrimsAndIgnoresCase() {
        var spec = item("FILL_BLANK", "alone", new BigDecimal("5"), "ALL_OR_NOTHING");
        assertThat(ObjectiveScoring.award(spec, " Alone ")).isEqualByComparingTo("5");
        assertThat(ObjectiveScoring.award(spec, "lonely")).isEqualByComparingTo("0");
    }

    @Test
    void multipleChoiceAllOrNothing() {
        var spec = item("MULTIPLE_CHOICE", "AB", new BigDecimal("10"), "ALL_OR_NOTHING");
        assertThat(ObjectiveScoring.award(spec, "AB")).isEqualByComparingTo("10");
        assertThat(ObjectiveScoring.award(spec, "BA")).isEqualByComparingTo("10");
        assertThat(ObjectiveScoring.award(spec, "A")).isEqualByComparingTo("0");
        assertThat(ObjectiveScoring.award(spec, "AC")).isEqualByComparingTo("0");
    }

    @Test
    void multipleChoicePartial() {
        var spec = item("MULTIPLE_CHOICE", "ABCD", new BigDecimal("10"), "PARTIAL");
        // 对 2 个、错 0 个 → 10 × 2/4 = 5
        assertThat(ObjectiveScoring.award(spec, "AB")).isEqualByComparingTo("5");
        // 对 2 个、错 1 个 → (2-1)/4 = 2.5
        assertThat(ObjectiveScoring.award(spec, "ABE")).isEqualByComparingTo("2.5");
        // 对 1 个、错 2 个 → 负数扣至 0
        assertThat(ObjectiveScoring.award(spec, "AEF")).isEqualByComparingTo("0");
        // 全对
        assertThat(ObjectiveScoring.award(spec, "DCBA")).isEqualByComparingTo("10");
        assertThat(ObjectiveScoring.fullyCorrect(spec, "ABCD")).isTrue();
        assertThat(ObjectiveScoring.fullyCorrect(spec, "AB")).isFalse();
    }

    @Test
    void essayAndMissingKeyAreZero() {
        assertThat(ObjectiveScoring.award(item("ESSAY", "A", new BigDecimal("15"), "ALL_OR_NOTHING"), "A"))
                .isEqualByComparingTo("0");
        assertThat(ObjectiveScoring.award(item("SINGLE_CHOICE", null, new BigDecimal("10"), "ALL_OR_NOTHING"), "A"))
                .isEqualByComparingTo("0");
    }
}
