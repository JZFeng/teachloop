package com.teachloop.sitting.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** 一场考试 / 一次布置：引用试卷 + 时间窗口 + 类型，班级见 offering_classes。 */
@Entity
@Table(name = "offerings")
public class Offering {

    @Id
    private UUID id;

    @Column(name = "paper_id", nullable = false)
    private UUID paperId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OfferingKind kind;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "timing_mode", nullable = false)
    private TimingMode timingMode = TimingMode.INDIVIDUAL;

    @Column(name = "grace_hours", nullable = false)
    private int graceHours = 3;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "feedback_language", nullable = false)
    private String feedbackLanguage = "zh-CN";

    @Column(name = "show_peer_overview", nullable = false)
    private boolean showPeerOverview = true;

    @Column(name = "publish_scores", nullable = false)
    private boolean publishScores = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OfferingStatus status = OfferingStatus.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "paper_snapshot", columnDefinition = "jsonb", nullable = false)
    private String paperSnapshot;

    @Column(name = "paper_version", nullable = false)
    private int paperVersion;

    @Column(name = "rubric_markdown", columnDefinition = "text")
    private String rubricMarkdown;

    @Column(name = "grading_prompt_template", columnDefinition = "text")
    private String gradingPromptTemplate;

    @Column(name = "grading_dispatched_at")
    private Instant gradingDispatchedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Offering() {
    }

    public Offering(UUID id, UUID paperId, OfferingKind kind, Instant startAt, Instant dueAt,
                    Integer durationMinutes, TimingMode timingMode, String paperSnapshot, int paperVersion,
                    String rubricMarkdown, String gradingPromptTemplate, Instant createdAt) {
        this.id = id;
        this.paperId = paperId;
        this.kind = kind;
        this.startAt = startAt;
        this.dueAt = dueAt;
        this.durationMinutes = durationMinutes;
        this.timingMode = timingMode;
        this.paperSnapshot = paperSnapshot;
        this.paperVersion = paperVersion;
        this.rubricMarkdown = rubricMarkdown;
        this.gradingPromptTemplate = gradingPromptTemplate;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getPaperId() { return paperId; }
    public OfferingKind getKind() { return kind; }
    public Instant getStartAt() { return startAt; }
    public Instant getDueAt() { return dueAt; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public TimingMode getTimingMode() { return timingMode; }
    public int getGraceHours() { return graceHours; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getFeedbackLanguage() { return feedbackLanguage; }
    public boolean isShowPeerOverview() { return showPeerOverview; }
    public boolean isPublishScores() { return publishScores; }
    public OfferingStatus getStatus() { return status; }
    public String getPaperSnapshot() { return paperSnapshot; }
    public int getPaperVersion() { return paperVersion; }
    public String getRubricMarkdown() { return rubricMarkdown; }
    public String getGradingPromptTemplate() { return gradingPromptTemplate; }
    public Instant getGradingDispatchedAt() { return gradingDispatchedAt; }

    public void setStatus(OfferingStatus status) { this.status = status; }
    public void setPublishScores(boolean v) { this.publishScores = v; }
    public void setGradingDispatchedAt(Instant t) { this.gradingDispatchedAt = t; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
    public void setGraceHours(int v) { this.graceHours = v; }
    public void setFeedbackLanguage(String v) { this.feedbackLanguage = v; }
    public void setShowPeerOverview(boolean v) { this.showPeerOverview = v; }
}
