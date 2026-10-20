package com.teachloop.grading.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次派发的运行记录（T018 / T092）：<b>一个 offering 一条 run</b>。
 *
 * <p>早期实现是「每份提交一条 run」，那样「一场只一条 run」不成立，也无法用
 * {@code grading_runs_offering_uk} 做并发幂等。现在 {@code offering_id} 非空且唯一，
 * {@code submission_id} 可空（保留给将来「单份重批」场景）；单份执行痕迹交给 T083 的 ai_calls。
 */
@Entity
@Table(name = "grading_runs")
public class GradingRun {

    @Id
    private UUID id;

    @Column(name = "offering_id")
    private UUID offeringId;

    @Column(name = "submission_id")
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

    public GradingRun(UUID id, UUID offeringId, String engine, Instant startedAt) {
        this.id = id;
        this.offeringId = offeringId;
        this.engine = engine;
        this.startedAt = startedAt;
    }

    public void finish(String state, String detail, Instant finishedAt) {
        this.state = state;
        this.detail = detail;
        this.finishedAt = finishedAt;
    }

    public UUID getId() { return id; }
    public UUID getOfferingId() { return offeringId; }
    public UUID getSubmissionId() { return submissionId; }
    public String getState() { return state; }
}
