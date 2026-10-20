package com.teachloop.paper.domain;

/** 多选计分策略（T196）：默认全对才得分；partial 按「对选 − 错选」扣至 0，不为负。 */
public enum ScoringStrategy { ALL_OR_NOTHING, PARTIAL }
