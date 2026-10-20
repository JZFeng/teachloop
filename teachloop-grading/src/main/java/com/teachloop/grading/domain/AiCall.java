package com.teachloop.grading.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * T083：每次模型调用的留痕（含重试）。只用于审计与成本分析，不参与判分。
 *
 * <p>raw_response 保留模型原始输出，便于事后复现「为什么这份判分失败」。
 */
@Entity
@Table(name = "ai_calls")
public class AiCall {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "raw_response", columnDefinition = "text")
    private String rawResponse;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiCall() {
    }

    public AiCall(UUID id, String model, String promptVersion, Integer tokensIn, Integer tokensOut,
                  long latencyMs, String rawResponse, Instant createdAt) {
        this.id = id;
        this.model = model;
        this.promptVersion = promptVersion;
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        this.latencyMs = latencyMs;
        this.rawResponse = rawResponse;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public Integer getTokensIn() { return tokensIn; }
    public Integer getTokensOut() { return tokensOut; }
    public long getLatencyMs() { return latencyMs; }
    public String getRawResponse() { return rawResponse; }
    public Instant getCreatedAt() { return createdAt; }
}
