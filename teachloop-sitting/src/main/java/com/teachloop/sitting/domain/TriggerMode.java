package com.teachloop.sitting.domain;

/** 作业 AI 批改的触发方式（决策 010）。考试不使用这个偏好。 */
public enum TriggerMode {
    /** 每晚教师设置的固定时刻批量派发（默认 02:30 Asia/Shanghai）。 */
    FIXED_TIME,
    /** 过了 due_at + grace_hours 后派发。 */
    DUE_DATE
}
