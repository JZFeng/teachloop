package com.teachloop.sitting.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "submissions")
public class Submission {

    @Id
    private UUID id;

    @Column(name = "offering_id", nullable = false)
    private UUID offeringId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo = 1;

    @Column(name = "input_kind", nullable = false)
    private String inputKind = "TEXT";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "content_text", columnDefinition = "text")
    private String contentText;

    @Column(name = "objective_total", nullable = false)
    private BigDecimal objectiveTotal = BigDecimal.ZERO;

    @Column(name = "subjective_total", nullable = false)
    private BigDecimal subjectiveTotal = BigDecimal.ZERO;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "auto_submitted", nullable = false)
    private boolean autoSubmitted = false;

    @Column(name = "late_by_seconds", nullable = false)
    private long lateBySeconds = 0;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "reject_reason", columnDefinition = "text")
    private String rejectReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "grading_state", nullable = false)
    private GradingState gradingState = GradingState.DONE;

    @Column(name = "grading_attempts", nullable = false)
    private int gradingAttempts = 0;

    @Column(name = "grading_error", columnDefinition = "text")
    private String gradingError;

    /** 交卷时间（含自动交卷）；null 表示仍在作答（草稿）。 */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Submission() {
    }

    public Submission(UUID id, UUID offeringId, UUID studentId, int attemptNo, String inputKind,
                      String contentText, GradingState gradingState, Instant createdAt) {
        this.id = id;
        this.offeringId = offeringId;
        this.studentId = studentId;
        this.attemptNo = attemptNo;
        this.inputKind = inputKind;
        this.contentText = contentText;
        this.gradingState = gradingState;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOfferingId() { return offeringId; }
    public UUID getStudentId() { return studentId; }
    public int getAttemptNo() { return attemptNo; }
    public String getInputKind() { return inputKind; }
    public boolean isActive() { return active; }
    public String getContentText() { return contentText; }
    public BigDecimal getObjectiveTotal() { return objectiveTotal; }
    public BigDecimal getSubjectiveTotal() { return subjectiveTotal; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public boolean isAutoSubmitted() { return autoSubmitted; }
    public long getLateBySeconds() { return lateBySeconds; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getRejectedAt() { return rejectedAt; }
    public String getRejectReason() { return rejectReason; }
    public GradingState getGradingState() { return gradingState; }
    public int getGradingAttempts() { return gradingAttempts; }
    public String getGradingError() { return gradingError; }

    public void setActive(boolean active) { this.active = active; }
    public void setSubmittedAt(Instant t) { this.submittedAt = t; }
    public void setAttemptNo(int n) { this.attemptNo = n; }
    public void setObjectiveTotal(BigDecimal v) { this.objectiveTotal = v; }
    public void setSubjectiveTotal(BigDecimal v) { this.subjectiveTotal = v; }
    public void setStartedAt(Instant t) { this.startedAt = t; }
    public void setDeadlineAt(Instant t) { this.deadlineAt = t; }
    public void setAutoSubmitted(boolean v) { this.autoSubmitted = v; }
    public void setLateBySeconds(long v) { this.lateBySeconds = v; }
    public void setRejectedAt(Instant t) { this.rejectedAt = t; }
    public void setRejectReason(String r) { this.rejectReason = r; }
    public void setGradingState(GradingState s) { this.gradingState = s; }
    public void setGradingError(String e) { this.gradingError = e; }
    public void setGradingAttempts(int n) { this.gradingAttempts = n; }
    public void setContentText(String t) { this.contentText = t; }
}
