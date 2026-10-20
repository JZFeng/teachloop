package com.teachloop.sitting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T071：final_total 只由 objective + subjective 派生，且负数直接拒绝。 */
class SubmissionTotalsTest {

    @Test
    @DisplayName("总分 = 客观 + 主观；负分被拒绝")
    void totalsAreDerivedAndNonNegative() {
        Submission s = new Submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "TEXT", null, GradingState.DRAFT, Instant.now());
        s.setObjectiveTotal(new BigDecimal("10.00"));
        s.setSubjectiveTotal(new BigDecimal("7.50"));
        assertThat(s.finalTotal()).isEqualByComparingTo("17.50");

        s.setObjectiveTotal(null);
        assertThat(s.finalTotal()).isEqualByComparingTo("7.50");

        assertThatThrownBy(() -> s.setSubjectiveTotal(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subjective_total");
        assertThatThrownBy(() -> s.setObjectiveTotal(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
