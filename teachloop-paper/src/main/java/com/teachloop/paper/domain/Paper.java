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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaperStatus status = PaperStatus.DRAFT;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "rubric_markdown", columnDefinition = "text")
    private String rubricMarkdown;

    @Column(name = "grading_prompt_template", columnDefinition = "text")
    private String gradingPromptTemplate;

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
    public PaperStatus getStatus() { return status; }
    public UUID getAuthorId() { return authorId; }
    public String getRubricMarkdown() { return rubricMarkdown; }
    public String getGradingPromptTemplate() { return gradingPromptTemplate; }

    public void setTitle(String title) { this.title = title; }
    public void setDefaultDurationMinutes(Integer m) { this.defaultDurationMinutes = m; }
    public void setTotalPoints(BigDecimal p) { this.totalPoints = p; }
    public void setRubricMarkdown(String md) { this.rubricMarkdown = md; }
    public void setGradingPromptTemplate(String p) { this.gradingPromptTemplate = p; }

    public void publish() {
        if (status == PaperStatus.PUBLISHED) {
            this.version += 1;
        }
        this.status = PaperStatus.PUBLISHED;
    }
}
