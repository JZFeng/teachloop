package com.teachloop.vocab.srs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T401：四种迁移、毕业边界（45 天 / 4 次）、易度上下限、毕业复查 180 天、参数可配。 */
class Sm2StrategyTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private final Sm2Strategy strategy = new Sm2Strategy(4, 45, 180, 1.30, 3.00, 5, 1.2, 1.3);

    @Test
    @DisplayName("忘记：→ LEARNING，5 分钟后，易度 −0.20，连续认识清零，lapses+1")
    void forget() {
        SrsDecision decision = strategy.schedule(SrsState.newWord(), Rating.AGAIN, NOW);
        assertThat(decision.state().state()).isEqualTo(LearningState.LEARNING);
        assertThat(decision.state().ease()).isEqualTo(2.30, within(1e-9));
        assertThat(decision.state().successes()).isZero();
        assertThat(decision.state().lapses()).isEqualTo(1);
        assertThat(decision.dueAt()).isEqualTo(NOW.plus(5, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("困难：新词基线 10 分钟，→ LEARNING，易度 −0.15")
    void hard() {
        SrsDecision decision = strategy.schedule(SrsState.newWord(), Rating.HARD, NOW);
        assertThat(decision.state().state()).isEqualTo(LearningState.LEARNING);
        assertThat(decision.state().ease()).isEqualTo(2.35, within(1e-9));
        assertThat(decision.dueAt()).isEqualTo(NOW.plus(10, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("认识：新词 → REVIEW，间隔 max(1 天, 0×易度)=1 天，易度不变")
    void good() {
        SrsDecision decision = strategy.schedule(SrsState.newWord(), Rating.GOOD, NOW);
        assertThat(decision.state().state()).isEqualTo(LearningState.REVIEW);
        assertThat(decision.state().intervalDays()).isEqualTo(1.0, within(1e-9));
        assertThat(decision.state().ease()).isEqualTo(2.50, within(1e-9));
        assertThat(decision.state().successes()).isEqualTo(1);
        assertThat(decision.dueAt()).isEqualTo(NOW.plus(1, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("容易：新词 → REVIEW，间隔 max(4 天, …)=4 天，易度 +0.15")
    void easy() {
        SrsDecision decision = strategy.schedule(SrsState.newWord(), Rating.EASY, NOW);
        assertThat(decision.state().state()).isEqualTo(LearningState.REVIEW);
        assertThat(decision.state().intervalDays()).isEqualTo(4.0, within(1e-9));
        assertThat(decision.state().ease()).isEqualTo(2.65, within(1e-9));
    }

    @Test
    @DisplayName("毕业：连续第 4 次认识且新间隔 ≥45 天 → GRADUATED，复查间隔取 180 天")
    void graduatesAtFourSuccessesAndFortyFiveDays() {
        SrsState before = new SrsState(LearningState.REVIEW, 50, 2.50, 3, 0);
        SrsDecision decision = strategy.schedule(before, Rating.GOOD, NOW);
        assertThat(decision.state().state()).isEqualTo(LearningState.GRADUATED);
        assertThat(decision.state().successes()).isEqualTo(4);
        assertThat(decision.state().intervalDays()).isEqualTo(180.0, within(1e-9));
        assertThat(decision.dueAt()).isEqualTo(NOW.plus(180, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("毕业边界：第 4 次认识但新间隔 <45 天 → 仍是 REVIEW")
    void staysReviewBelowFortyFiveDays() {
        SrsState before = new SrsState(LearningState.REVIEW, 17, 2.50, 3, 0);
        SrsDecision decision = strategy.schedule(before, Rating.GOOD, NOW);
        assertThat(decision.state().state()).isEqualTo(LearningState.REVIEW);
        assertThat(decision.state().intervalDays()).isEqualTo(42.5, within(1e-9));
    }

    @Test
    @DisplayName("易度上下限：1.35 忘记 → 1.30；2.95 容易 → 3.00")
    void easeClamp() {
        SrsState low = new SrsState(LearningState.REVIEW, 2, 1.35, 0, 0);
        assertThat(strategy.schedule(low, Rating.AGAIN, NOW).state().ease())
                .isEqualTo(1.30, within(1e-9));
        SrsState high = new SrsState(LearningState.REVIEW, 2, 2.95, 0, 0);
        assertThat(strategy.schedule(high, Rating.EASY, NOW).state().ease())
                .isEqualTo(3.00, within(1e-9));
    }

    @Test
    @DisplayName("已掌握的卡：认识仍保持 GRADUATED，按 ≥180 天复查；不认识回 LEARNING")
    void graduatedReview() {
        SrsState graduated = new SrsState(LearningState.GRADUATED, 180, 2.5, 4, 0);
        SrsDecision good = strategy.schedule(graduated, Rating.GOOD, NOW);
        assertThat(good.state().state()).isEqualTo(LearningState.GRADUATED);
        assertThat(good.state().intervalDays()).isEqualTo(180.0, within(1e-9));

        SrsDecision again = strategy.schedule(graduated, Rating.AGAIN, NOW);
        assertThat(again.state().state()).isEqualTo(LearningState.LEARNING);
        assertThat(again.dueAt()).isEqualTo(NOW.plus(5, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("参数可配：successes=2 / min-interval=10 时第 2 次认识就能毕业")
    void configurationChangesBehaviour() {
        Sm2Strategy custom = new Sm2Strategy(2, 10, 60, 1.30, 3.00, 5, 1.2, 1.3);
        SrsState before = new SrsState(LearningState.REVIEW, 5, 2.5, 1, 0);
        SrsDecision decision = custom.schedule(before, Rating.GOOD, NOW);
        assertThat(decision.state().state()).isEqualTo(LearningState.GRADUATED);
        assertThat(decision.state().intervalDays()).isEqualTo(60.0, within(1e-9));
    }
}
