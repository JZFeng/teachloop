package com.teachloop.sitting.domain;

/** 工作项状态。pending 就是队列，不需要独立队列表。 */
public enum GradingState { PENDING, RUNNING, DONE, FAILED, SKIPPED }
