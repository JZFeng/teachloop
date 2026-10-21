package com.teachloop.grading.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 复核结论（US-11 / T100–T104）。
 *
 * <p>状态三态加待办：{@code PENDING → APPROVED | EDITED | REJECTED}。
 * {@code original_subjective} 是批改完成时捕获的 AI 主观草稿，教师改分时保留原值，
 * 审计用「AI 草稿 → 最终分」的前后对比，不要求教师自己判断改动大小。
 */
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    private UUID id;

    @Column(name = "submission_id", nullable = false, unique = true)
    private UUID submissionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(name = "teacher_id")
    private UUID teacherId;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(columnDefinition = "text")
    private String strengths;

    @Column(columnDefinition = "text")
    private String suggestions;

    /** 批改完成时的 AI 主观分；之后教师改分不改这一列。 */
    @Column(name = "original_subjective", precision = 8, scale = 2)
    private BigDecimal originalSubjective;

    /** T253：AI 的逐项意见（JSON 数组：[{name, points, maxPoints, comment}]）。 */
    @Column(name = "ai_criteria", columnDefinition = "text")
    private String aiCriteria;

    /** T253：AI 的「优点 / 建议」文本。 */
    @Column(name = "ai_feedback", columnDefinition = "text")
    private String aiFeedback;

    /** 非空即已发布；撤回时置回 null。 */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Review() {
    }

    public Review(UUID id, UUID submissionId, UUID teacherId, ReviewStatus status, String comment,
                  Instant createdAt) {
        this(id, submissionId, teacherId, status, comment, null, createdAt);
    }

    public Review(UUID id, UUID submissionId, UUID teacherId, ReviewStatus status, String comment,
                  BigDecimal originalSubjective, Instant createdAt) {
        this.id = id;
        this.submissionId = submissionId;
        this.teacherId = teacherId;
        this.status = status;
        this.comment = comment;
        this.originalSubjective = originalSubjective;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getSubmissionId() { return submissionId; }
    public ReviewStatus getStatus() { return status; }
    public UUID getTeacherId() { return teacherId; }
    public String getComment() { return comment; }
    public String getStrengths() { return strengths; }
    public String getSuggestions() { return suggestions; }
    public BigDecimal getOriginalSubjective() { return originalSubjective; }
    public String getAiCriteria() { return aiCriteria; }
    public String getAiFeedback() { return aiFeedback; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isPublished() {
        return publishedAt != null;
    }

    /** T100：三态结论（原样通过 / 改后通过 / 弃用重批）。 */
    public void decide(ReviewStatus status, UUID teacherId, String comment) {
        this.status = status;
        this.teacherId = teacherId;
        this.comment = comment;
    }

    /** T101：只改主观分与文本；状态置 edited，original_subjective 保留 AI 草稿。 */
    public void edit(UUID teacherId, String comment, String strengths, String suggestions) {
        this.teacherId = teacherId;
        this.comment = comment;
        this.strengths = strengths;
        this.suggestions = suggestions;
        this.status = ReviewStatus.EDITED;
    }

    /** T103：发布（仅 approved / edited 由接口校验）。 */
    public void publish(Instant at) {
        this.publishedAt = at;
    }

    /**
     * D-15：老师先打开复核详情（懒建 PENDING、没有草稿）之后 AI 才完成时，补记 AI 草稿。
     *
     * <p>只在没有草稿、老师还没改过分时写入；人工分或已决策的复核不接受 AI 回填。
     */
    public void captureAiDraft(BigDecimal points) {
        captureAiDraft(points, null, null);
    }

    /**
     * T253：把 AI 草稿（分数 + 逐项意见 + 评语）一起捕获。
     *
     * <p>老师一旦人工改过分或已发布，AI 的任何结果都不许覆盖；同一份提交重跑 AI 时，
     * 只要老师还没动过，就更新成最新一次的结果。
     */
    public void captureAiDraft(BigDecimal points, String criteriaJson, String feedback) {
        if (this.teacherId != null || isPublished()) {
            return;
        }
        if (points != null) {
            this.originalSubjective = points;
        }
        if (criteriaJson != null) {
            this.aiCriteria = criteriaJson;
        }
        if (feedback != null) {
            this.aiFeedback = feedback;
        }
    }

    /** T103：撤回；清 published_at，内容与状态保留以便修改后重新发布。 */
    public void withdraw() {
        this.publishedAt = null;
    }
}
