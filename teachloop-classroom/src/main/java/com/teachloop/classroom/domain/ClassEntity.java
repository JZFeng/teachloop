package com.teachloop.classroom.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "classes")
public class ClassEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;

    @Column(name = "invite_code", nullable = false, unique = true)
    private String inviteCode;

    @Column(nullable = false)
    private boolean archived = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 011 / US-95：收费字段（V077 落地，三列都带默认值）。
     * 第一期只写 {@link ClassPay.Mode#FREE}；存量班级迁移后同样是 FREE / 0 / CNY。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "pay_mode", nullable = false)
    private ClassPay.Mode payMode = ClassPay.Mode.FREE;

    /** 单位「分」，避免浮点误差；第一期恒为 0。 */
    @Column(name = "price_cents", nullable = false)
    private int priceCents = 0;

    /** ISO-4217 三位大写；第一期恒为 CNY。 */
    @Column(nullable = false)
    private String currency = ClassPay.DEFAULT_CURRENCY;

    protected ClassEntity() {
    }

    public ClassEntity(UUID id, String name, UUID teacherId, String inviteCode, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.teacherId = teacherId;
        this.inviteCode = inviteCode;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getTeacherId() { return teacherId; }
    public String getInviteCode() { return inviteCode; }
    public boolean isArchived() { return archived; }
    public ClassPay.Mode getPayMode() { return payMode; }
    public int getPriceCents() { return priceCents; }
    public String getCurrency() { return currency; }

    /** 付费班判定：null 视为免费（列 NOT NULL，兜底不误伤存量数据）。入班用它决定 409。 */
    public boolean isPaid() {
        return payMode != null && payMode != ClassPay.Mode.FREE;
    }

    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
    public void setArchived(boolean archived) { this.archived = archived; }
}
