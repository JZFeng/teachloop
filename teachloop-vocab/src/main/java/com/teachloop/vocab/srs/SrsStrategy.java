package com.teachloop.vocab.srs;

import java.time.Instant;

/** 可替换调度策略（plan §6）：默认 SM-2；换策略不改表结构（AC-53.5）。 */
public interface SrsStrategy {

    SrsDecision schedule(SrsState current, Rating rating, Instant now);
}
