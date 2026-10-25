package com.teachloop.paper.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 试卷只是内容，不属于任何班级（US-4）。 */
@Entity
@Table(name = "papers")
public class Paper {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(name = "default_duration_minutes")
    private Integer defaultDurationMinutes;

    @Column(name = "total_points", nullable = false)
    private BigDecimal totalPoints = BigDecimal.ZERO;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "rubric_version", nullable = false)
    private int rubricVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaperStatus status = PaperStatus.DRAFT;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "rubric_markdown", columnDefinition = "text")
    private String rubricMarkdown;

    @Column(name = "grading_prompt_template", columnDefinition = "text")
    private String gradingPromptTemplate;

    /**
     * D-34：教师手填的期望总分，只做发布闸门（AC-4.26），**不参与** {@code totalPoints} 的计算
     * （判分信封与快照仍读 {@code totalPoints}，见决策 021「目标总分不覆盖自动合计」）。
     * 可空：旧卷全空，空时发布跳过「合计 == 目标」这条校验。
     */
    @Column(name = "target_total_points")
    private BigDecimal targetTotalPoints;

    /** D-34：命题人姓名，自由文本、不做账号外键（AC-4.27）；空则由创建人显示名兜底。 */
    @Column(name = "author_name", length = 80)
    private String authorName;

    /** D-34：审题人姓名，自由文本、可空。 */
    @Column(name = "reviewer_name", length = 80)
    private String reviewerName;

    /**
     * D-34：最近一次「生成考题」提交的原文（内容唯一来源，AC-4.24④）。
     *
     * <p>只用于回填编辑器；旧卷为空，此时编辑器空、预览表照常显示服务端题项。</p>
     */
    @Column(name = "content_markdown", columnDefinition = "text")
    private String contentMarkdown;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Paper() {
    }

    public Paper(UUID id, String title, Integer defaultDurationMinutes, UUID authorId, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.defaultDurationMinutes = defaultDurationMinutes;
        this.authorId = authorId;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public Integer getDefaultDurationMinutes() { return defaultDurationMinutes; }
    public BigDecimal getTotalPoints() { return totalPoints; }
    public int getVersion() { return version; }
    public int getRubricVersion() { return rubricVersion; }
    public PaperStatus getStatus() { return status; }
    public UUID getAuthorId() { return authorId; }
    public String getRubricMarkdown() { return rubricMarkdown; }
    public String getGradingPromptTemplate() { return gradingPromptTemplate; }

    public void setTitle(String title) { this.title = title; }
    public void setDefaultDurationMinutes(Integer m) { this.defaultDurationMinutes = m; }
    public void setTotalPoints(BigDecimal p) { this.totalPoints = p; }
    public void setRubricMarkdown(String md) { this.rubricMarkdown = md; }
    public void setGradingPromptTemplate(String p) { this.gradingPromptTemplate = p; }

    public BigDecimal getTargetTotalPoints() { return targetTotalPoints; }
    public void setTargetTotalPoints(BigDecimal points) { this.targetTotalPoints = points; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String name) { this.authorName = name; }
    public String getReviewerName() { return reviewerName; }
    public void setReviewerName(String name) { this.reviewerName = name; }
    public String getContentMarkdown() { return contentMarkdown; }
    public void setContentMarkdown(String markdown) { this.contentMarkdown = markdown; }

    /** T206：归档后不再出现在默认列表的可用项里。 */
    public void archive() {
        this.status = PaperStatus.ARCHIVED;
    }

    public void publish() {
        // T054：首次发布保持 v1；之后每次重新发布递增内容版本与评分标准版本。
        if (status == PaperStatus.PUBLISHED) {
            this.version += 1;
            this.rubricVersion += 1;
        }
        this.status = PaperStatus.PUBLISHED;
    }
}
