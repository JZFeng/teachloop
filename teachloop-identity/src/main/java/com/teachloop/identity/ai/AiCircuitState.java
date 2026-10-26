package com.teachloop.identity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * D-31：AI 调用熔断状态（每个 provider 一行）。
 *
 * <p>状态只在这里推进；app 与 worker 两个进程都通过条件更新抢占半开试探权。
 */
@Entity
@Table(name = "ai_circuit_state")
public class AiCircuitState {

    @Id
    @Column(name = "provider_key", length = 80)
    private String providerKey;

    @Column(nullable = false, length = 16)
    private String state = "CLOSED";

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Column(name = "streak_started_at")
    private Instant streakStartedAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "half_open_started_at")
    private Instant halfOpenStartedAt;

    @Column(name = "last_error_kind", length = 32)
    private String lastErrorKind;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 决策 022 D5：连续成功份数（成功 +1、失败清零），回切主 provider 的判定依据。 */
    @Column(name = "success_streak", nullable = false)
    private int successStreak = 0;

    protected AiCircuitState() {
    }

    public AiCircuitState(String providerKey, Instant now) {
        this.providerKey = providerKey;
        this.updatedAt = now;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public Instant getStreakStartedAt() {
        return streakStartedAt;
    }

    public void setStreakStartedAt(Instant streakStartedAt) {
        this.streakStartedAt = streakStartedAt;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getHalfOpenStartedAt() {
        return halfOpenStartedAt;
    }

    public void setHalfOpenStartedAt(Instant halfOpenStartedAt) {
        this.halfOpenStartedAt = halfOpenStartedAt;
    }

    public String getLastErrorKind() {
        return lastErrorKind;
    }

    public void setLastErrorKind(String lastErrorKind) {
        this.lastErrorKind = lastErrorKind;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public void setLastFailureAt(Instant lastFailureAt) {
        this.lastFailureAt = lastFailureAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getSuccessStreak() {
        return successStreak;
    }

    public void setSuccessStreak(int successStreak) {
        this.successStreak = successStreak;
    }
}
