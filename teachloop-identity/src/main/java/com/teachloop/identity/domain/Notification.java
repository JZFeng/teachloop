package com.teachloop.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 一条站内通知（T208 / T114）。接收方是 users.id，标题正文由调用方按语言生成。
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

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Notification(UUID id, UUID recipientId, String kind, String targetType, String targetId,
                        String title, String body, Instant createdAt) {
        this.id = id;
        this.recipientId = recipientId;
        this.kind = kind;
        this.targetType = targetType;
        this.targetId = targetId;
        this.title = title;
        this.body = body;
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
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
}
