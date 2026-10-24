package com.teachloop.vocab.srs;

/**
 * 策略无关的复习状态（进度表只存这些字段，换 FSRS 等不改表结构）。
 *
 * <p>{@code successes} 是「连续认识」次数；任何「不认识/困难」清零。
 * {@code intervalDays} 允许小于 1（5 分钟 = 5/1440 天）。
 */
public record SrsState(LearningState state, double intervalDays, double ease, int successes, int lapses) {

    /** 新词：易度 2.50，间隔 0，successes 0。 */
    public static SrsState newWord() {
        return new SrsState(LearningState.NEW, 0, 2.50, 0, 0);
    }
}
