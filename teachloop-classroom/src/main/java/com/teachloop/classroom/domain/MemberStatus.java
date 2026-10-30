package com.teachloop.classroom.domain;

/**
 * 成员状态（012-class-lifecycle / FR-214）。
 *
 * <p>退班与移出不删行，只把 {@code ACTIVE} 改成 {@code LEFT}：成绩与提交按 {@code student_id}
 * 存放在 sitting 的 {@code submissions}，与成员状态无关，删行会让历史查不回来（FR-215）。
 */
public enum MemberStatus {
    ACTIVE,
    LEFT
}
