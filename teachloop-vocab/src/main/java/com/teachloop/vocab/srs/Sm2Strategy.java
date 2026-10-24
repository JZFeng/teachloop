package com.teachloop.vocab.srs;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 默认 SM-2 策略（spec §5 / plan §6）。全部数值来自 `teachloop.vocab.srs.*`，
 * 改配置重启生效，不写死在调用方（FR-106）。
 *
 * <p>内部映射固定：界面「认识」→ {@link Rating#GOOD}、「不认识」→ {@link Rating#AGAIN}
 * （AC-53.8）。间隔以「天」为单位（5 分钟 = 5/1440 天），due_at 用绝对时间。
 */
@Component
public class Sm2Strategy implements SrsStrategy {

    private static final double MINUTES_PER_DAY = 1.0 / (24 * 60);
    private static final double SECONDS_PER_DAY = 24 * 60 * 60;

    private final int graduationSuccesses;
    private final double graduationMinIntervalDays;
    private final double graduationReviewIntervalDays;
    private final double easeMin;
    private final double easeMax;
    private final double forgetMinutes;
    private final double hardMultiplier;
    private final double easyMultiplier;

    public Sm2Strategy(
            @Value("${teachloop.vocab.srs.graduation.successes:4}") int graduationSuccesses,
            @Value("${teachloop.vocab.srs.graduation.min-interval-days:45}") double graduationMinIntervalDays,
            @Value("${teachloop.vocab.srs.graduation.review-interval-days:180}") double graduationReviewIntervalDays,
            @Value("${teachloop.vocab.srs.ease.min:1.30}") double easeMin,
            @Value("${teachloop.vocab.srs.ease.max:3.00}") double easeMax,
            @Value("${teachloop.vocab.srs.forget-minutes:5}") double forgetMinutes,
            @Value("${teachloop.vocab.srs.hard-multiplier:1.2}") double hardMultiplier,
            @Value("${teachloop.vocab.srs.easy-multiplier:1.3}") double easyMultiplier) {
        this.graduationSuccesses = graduationSuccesses;
        this.graduationMinIntervalDays = graduationMinIntervalDays;
        this.graduationReviewIntervalDays = graduationReviewIntervalDays;
        this.easeMin = easeMin;
        this.easeMax = easeMax;
        this.forgetMinutes = forgetMinutes;
        this.hardMultiplier = hardMultiplier;
        this.easyMultiplier = easyMultiplier;
    }

    @Override
    public SrsDecision schedule(SrsState current, Rating rating, Instant now) {
        return switch (rating) {
            case AGAIN -> forget(current, now);
            case HARD -> hard(current, now);
            case GOOD -> known(current, now, false);
            case EASY -> known(current, now, true);
        };
    }

    private SrsDecision forget(SrsState current, Instant now) {
        SrsState next = new SrsState(LearningState.LEARNING, 0,
                clampEase(current.ease() - 0.20), 0, current.lapses() + 1);
        return new SrsDecision(next, now.plusSeconds(Math.round(forgetMinutes * 60)));
    }

    private SrsDecision hard(SrsState current, Instant now) {
        double interval = current.intervalDays() > 0
                ? current.intervalDays() * hardMultiplier
                : 10 * MINUTES_PER_DAY; // 新词基线 10 分钟（spec §5 补）
        SrsState next = new SrsState(LearningState.LEARNING, interval,
                clampEase(current.ease() - 0.15), 0, current.lapses());
        return new SrsDecision(next, due(now, interval));
    }

    private SrsDecision known(SrsState current, Instant now, boolean easy) {
        double ease = easy ? clampEase(current.ease() + 0.15) : current.ease();
        double baseInterval = current.intervalDays();
        double nextInterval = easy
                ? Math.max(4.0, baseInterval * current.ease() * easyMultiplier)
                : Math.max(1.0, baseInterval * current.ease());
        int successes = current.successes() + 1;

        // 已毕业：保持已掌握，按复查间隔安排下一次（任何一次不认识已在 forget 分支回学习中）
        if (current.state() == LearningState.GRADUATED) {
            double reviewInterval = Math.max(graduationReviewIntervalDays, baseInterval);
            return new SrsDecision(new SrsState(LearningState.GRADUATED, reviewInterval, ease,
                    successes, current.lapses()), due(now, reviewInterval));
        }

        boolean graduates = successes >= graduationSuccesses
                && nextInterval >= graduationMinIntervalDays;
        if (graduates) {
            double reviewInterval = Math.max(nextInterval, graduationReviewIntervalDays);
            return new SrsDecision(new SrsState(LearningState.GRADUATED, reviewInterval, ease,
                    successes, current.lapses()), due(now, reviewInterval));
        }
        return new SrsDecision(new SrsState(LearningState.REVIEW, nextInterval, ease,
                successes, current.lapses()), due(now, nextInterval));
    }

    private Instant due(Instant now, double intervalDays) {
        return now.plusSeconds(Math.round(intervalDays * SECONDS_PER_DAY));
    }

    private double clampEase(double ease) {
        return Math.max(easeMin, Math.min(easeMax, ease));
    }
}
