package com.teachloop.sitting.domain;

/**
 * 工作项状态。pending 就是队列，不需要独立队列表。
 * DRAFT 表示「已开始作答、还没交卷」，不进入批改队列（T199）。
 */
public enum GradingState { DRAFT, PENDING, RUNNING, DONE, FAILED, SKIPPED }
