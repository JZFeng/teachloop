package com.teachloop.paper.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 替换重建前的旧内容快照（D-34 / 决策 021）。
 *
 * <p>「生成考题」按 replace=true 重建时会先清空本卷的部分 / 题项 / 评分项，删之前把这三样
 * 序列化成一条 JSON 存下来，只用于误删恢复（v1 没有界面撤销按钮，恢复走运维 SQL）。
 * 每卷只保留最近 3 条，由 {@code PaperEndpoint} 写入后裁剪。</p>
 *
 * <p>只存快照事实，不存原文：原文在 {@code papers.content_markdown}。</p>
 */
@Entity
@Table(name = "paper_content_snapshots")
public class PaperContentSnapshot {

    @Id
    private UUID id;

    @Column(name = "paper_id", nullable = false)
    private UUID paperId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 触发原因：目前只有 {@code generate}（「生成考题」替换重建）。 */
    @Column(nullable = false, length = 40)
    private String reason;

    /** {@code {"sections":[…],"items":[…],"criteria":[…]}}；jsonb 列，用 String 承载。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payloadJson;

    protected PaperContentSnapshot() {
    }

    public PaperContentSnapshot(UUID id, UUID paperId, Instant createdAt, String reason, String payloadJson) {
        this.id = id;
        this.paperId = paperId;
        this.createdAt = createdAt;
        this.reason = reason;
        this.payloadJson = payloadJson;
    }

    public UUID getId() { return id; }
    public UUID getPaperId() { return paperId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getReason() { return reason; }
    public String getPayloadJson() { return payloadJson; }
}
