package com.teachloop.vocab.srs;

import java.time.Instant;

/** 调度结果：新状态 + 下次到期时间。 */
public record SrsDecision(SrsState state, Instant dueAt) {
}
