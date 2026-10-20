package com.teachloop.sitting.domain;

/**
 * 工作项状态。pending 就是队列，不需要独立队列表。
 *
 * <p>DRAFT：已开始作答、还没交卷，不进入批改队列（T199）。
 * QUEUED：已交卷且需要批改，但这一场还没派发（T092 派发时批量转 PENDING）。
 * PENDING：已入队，worker 可以领取。
 */
public enum GradingState { DRAFT, QUEUED, PENDING, RUNNING, DONE, FAILED, SKIPPED }
