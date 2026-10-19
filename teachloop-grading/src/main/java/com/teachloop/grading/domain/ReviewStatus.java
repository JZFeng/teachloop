package com.teachloop.grading.domain;

/** 三态复核：原样通过 / 改后通过 / 弃用重批（改动程度由系统自动记录，老师不必判断小改还是大改）。 */
public enum ReviewStatus {
    PENDING,
    APPROVED,
    EDITED,
    REJECTED
}
