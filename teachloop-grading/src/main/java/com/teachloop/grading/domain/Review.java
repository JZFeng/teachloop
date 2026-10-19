package com.teachloop.grading.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Review() {
    }

    public Review(UUID id, UUID submissionId, UUID teacherId, ReviewStatus status, String comment, Instant createdAt) {
        this.id = id;
        this.submissionId = submissionId;
        this.teacherId = teacherId;
        this.status = status;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getSubmissionId() { return submissionId; }
    public ReviewStatus getStatus() { return status; }
    public UUID getTeacherId() { return teacherId; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }

    public void decide(ReviewStatus status, UUID teacherId, String comment) {
        this.status = status;
        this.teacherId = teacherId;
        this.comment = comment;
    }
}
