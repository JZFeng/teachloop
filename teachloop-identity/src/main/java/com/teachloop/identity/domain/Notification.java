package com.teachloop.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一条站内通知（T208 / T114）。接收方是 users.id。
 *
 * <p>A1：文案不再由调用方按语言拼好落库——{@code payload} 存事实参数，读取时按 Accept-Language 渲染；
 * {@code title}/{@code body} 只作为历史行与未知 kind 的回退原文（新行为 null）。
 *
 * <p>已读用 {@code read_at} 表示，不用布尔列：既能判未读，也保留「什么时候读的」。
 * 未读查询走 {@code (recipient_id, read_at)} 索引。
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "target_type")
    private String targetType;

    @Column(name = "target_id")
    private String targetId;

    @Column
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    /**
     * A1：通知的事实参数（JSON），读取时按 Accept-Language 渲染成 title/body。
     *
     * <p>为 null 的是历史行或没有模板的 kind，直接显示 title/body 原文。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Notification(UUID id, UUID recipientId, String kind, String targetType, String targetId,
                        String title, String body, String payloadJson, Instant createdAt) {
        this.id = id;
        this.recipientId = recipientId;
        this.kind = kind;
        this.targetType = targetType;
        this.targetId = targetId;
        this.title = title;
        this.body = body;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
    }

    /** 幂等：已读的通知保留第一次已读时间。 */
    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    public boolean isRead() {
        return readAt != null;
    }

    public UUID getId() { return id; }
    public UUID getRecipientId() { return recipientId; }
    public String getKind() { return kind; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
}
