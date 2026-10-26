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

    /** T060：消毒后的富文本原文（回显用）；content_text 是从它提取的纯文本。 */
    @Column(name = "content_html", columnDefinition = "text")
    private String contentHtml;

    /** T061：附件在 BlobStore 中的 key；不存二进制、不暴露存储根目录。 */
    @Column(name = "attachment_path", columnDefinition = "text")
    private String attachmentPath;

    @Column(name = "attachment_name")
    private String attachmentName;

    @Column(name = "attachment_mime")
    private String attachmentMime;

    /** T066：文件解析失败或无文本层，不参与评分（不产生 0 分）。 */
    @Column(nullable = false)
    private boolean unreadable = false;

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

    /** 决策 022 D3：这份提交由哪条 provider 判的；人工判分或未判时为 null。 */
    @Column(name = "graded_provider", length = 64)
    private String gradedProvider;

    @Enumerated(EnumType.STRING)
    @Column(name = "grading_state", nullable = false)
    private GradingState gradingState = GradingState.DONE;

    @Column(name = "grading_attempts", nullable = false)
    private int gradingAttempts = 0;

    @Column(name = "grading_error", columnDefinition = "text")
    private String gradingError;

    /** D-31：失败时刻；熔断恢复后自动重派的窗口依据。 */
    @Column(name = "grading_failed_at")
    private Instant gradingFailedAt;

    /** D-31：被熔断恢复自动重派的次数；上限 1，避免「失败→恢复→重派→再失败」的循环。 */
    @Column(name = "grading_circuit_requeues", nullable = false)
    private int gradingCircuitRequeues = 0;

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
    public String getContentHtml() { return contentHtml; }
    public String getAttachmentPath() { return attachmentPath; }
    public String getAttachmentName() { return attachmentName; }
    public String getAttachmentMime() { return attachmentMime; }
    public boolean isUnreadable() { return unreadable; }
    public BigDecimal getObjectiveTotal() { return objectiveTotal; }
    public BigDecimal getSubjectiveTotal() { return subjectiveTotal; }

    /** T071：最终分只由客观分 + 主观分派生，避免三个数各写一处而漂移。 */
    public BigDecimal finalTotal() {
        return objectiveTotal.add(subjectiveTotal);
    }
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
    public Instant getGradingFailedAt() { return gradingFailedAt; }

    public void setActive(boolean active) { this.active = active; }
    public void setInputKind(String kind) { this.inputKind = kind; }
    public void setSubmittedAt(Instant t) { this.submittedAt = t; }
    public void setAttemptNo(int n) { this.attemptNo = n; }
    public void setObjectiveTotal(BigDecimal v) {
        this.objectiveTotal = nonNegative(v, "objective_total");
    }

    public void setSubjectiveTotal(BigDecimal v) {
        this.subjectiveTotal = nonNegative(v, "subjective_total");
    }

    private static BigDecimal nonNegative(BigDecimal v, String field) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v.signum() < 0) {
            throw new IllegalArgumentException(field + " 不能为负: " + v);
        }
        return v;
    }
    public void setStartedAt(Instant t) { this.startedAt = t; }
    public void setDeadlineAt(Instant t) { this.deadlineAt = t; }
    public void setAutoSubmitted(boolean v) { this.autoSubmitted = v; }
    public void setLateBySeconds(long v) { this.lateBySeconds = v; }
    public void setRejectedAt(Instant t) { this.rejectedAt = t; }
    public void setRejectReason(String r) { this.rejectReason = r; }
    public void setGradingState(GradingState s) { this.gradingState = s; }
    public void setGradingError(String e) { this.gradingError = e; }
    public void setGradingAttempts(int n) { this.gradingAttempts = n; }
    public void setGradingFailedAt(Instant t) { this.gradingFailedAt = t; }
    public int getGradingCircuitRequeues() { return gradingCircuitRequeues; }
    public void setGradingCircuitRequeues(int n) { this.gradingCircuitRequeues = n; }
    public void setContentText(String t) { this.contentText = t; }
    public void setContentHtml(String html) { this.contentHtml = html; }
    public void setAttachmentPath(String path) { this.attachmentPath = path; }
    public void setAttachmentName(String name) { this.attachmentName = name; }
    public void setAttachmentMime(String mime) { this.attachmentMime = mime; }
    public void setUnreadable(boolean v) { this.unreadable = v; }

    public String getGradedProvider() {
        return gradedProvider;
    }

    public void setGradedProvider(String gradedProvider) {
        this.gradedProvider = gradedProvider;
    }
}
