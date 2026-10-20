package com.teachloop.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** T036：教师邀请码，一次性、可过期、可停用。 */
@Entity
@Table(name = "teacher_invites")
public class TeacherInvite {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_by")
    private UUID usedBy;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(nullable = false)
    private boolean disabled = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TeacherInvite() {
    }

    public TeacherInvite(UUID id, String code, UUID createdBy, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.code = code;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public boolean usable(Instant now) {
        return !disabled && usedAt == null && expiresAt.isAfter(now);
    }

    public void consume(UUID userId, Instant now) {
        this.usedBy = userId;
        this.usedAt = now;
    }

    public void disable() {
        this.disabled = true;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getUsedBy() { return usedBy; }
    public Instant getUsedAt() { return usedAt; }
    public boolean isDisabled() { return disabled; }
    public Instant getCreatedAt() { return createdAt; }
}
