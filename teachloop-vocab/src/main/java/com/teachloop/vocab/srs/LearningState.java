package com.teachloop.vocab.srs;

/** 进度状态机（与 vocab_progress.state 的取值一致）。 */
public enum LearningState {
    NEW,
    LEARNING,
    REVIEW,
    GRADUATED
}
