package com.teachloop.sitting.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 教师级作业批改时机（决策 010）。
 *
 * <p>没有行时按默认值解释（02:30 Asia/Shanghai、固定时间、启动补跑），
 * 不要求每个教师先保存一次设置。`lastFixedRunAt` 只用于固定时间模式：
 * 同一教师同一本地日期最多跑一次，避免每分钟扫描把后续提交立刻派发。
 */
@Entity
@Table(name = "teacher_grading_preferences")
public class TeacherGradingPreference {

    public static final LocalTime DEFAULT_TIME = LocalTime.of(2, 30);
    public static final String DEFAULT_ZONE = "Asia/Shanghai";

    @Id
    @Column(name = "teacher_id")
    private UUID teacherId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_mode", nullable = false)
    private TriggerMode triggerMode = TriggerMode.FIXED_TIME;

    @Column(name = "fixed_time", nullable = false)
    private LocalTime fixedTime = DEFAULT_TIME;

    @Column(name = "time_zone", nullable = false)
    private String timeZone = DEFAULT_ZONE;

    @Column(name = "catch_up", nullable = false)
    private boolean catchUp = true;

    @Column(name = "last_fixed_run_at")
    private Instant lastFixedRunAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected TeacherGradingPreference() {
    }

    public TeacherGradingPreference(UUID teacherId, Instant updatedAt) {
        this.teacherId = teacherId;
        this.updatedAt = updatedAt;
    }

    public UUID getTeacherId() { return teacherId; }
    public TriggerMode getTriggerMode() { return triggerMode; }
    public LocalTime getFixedTime() { return fixedTime; }
    public String getTimeZone() { return timeZone; }
    public boolean isCatchUp() { return catchUp; }
    public Instant getLastFixedRunAt() { return lastFixedRunAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }

    public void setTriggerMode(TriggerMode v) { this.triggerMode = v; }
    public void setFixedTime(LocalTime v) { this.fixedTime = v; }
    public void setTimeZone(String v) { this.timeZone = v; }
    public void setCatchUp(boolean v) { this.catchUp = v; }
    public void setLastFixedRunAt(Instant v) { this.lastFixedRunAt = v; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public void setUpdatedBy(UUID v) { this.updatedBy = v; }
}
