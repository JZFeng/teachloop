package com.teachloop.grading.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "grading_runs")
public class GradingRun {

    @Id
    private UUID id;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(nullable = false)
    private String engine;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(nullable = false)
    private String state = "running";

    @Column(columnDefinition = "text")
    private String detail;

    protected GradingRun() {
    }

    public GradingRun(UUID id, UUID submissionId, String engine, Instant startedAt) {
        this.id = id;
        this.submissionId = submissionId;
        this.engine = engine;
        this.startedAt = startedAt;
    }

    public void finish(String state, String detail, Instant finishedAt) {
        this.state = state;
        this.detail = detail;
        this.finishedAt = finishedAt;
    }

    public UUID getId() { return id; }
    public UUID getSubmissionId() { return submissionId; }
    public String getState() { return state; }
}
