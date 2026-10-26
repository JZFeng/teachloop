package com.teachloop.identity.ai;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 管理员 AI 配置（单行 id=1）；密钥列只存密文。 */
@Entity
@Table(name = "ai_settings")
public class AiSettingsEntity {

    @Id
    private Integer id;

    @Column(nullable = false)
    private String provider;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(nullable = false)
    private String model;

    @Column(name = "api_key_cipher", columnDefinition = "text")
    private String apiKeyCipher;

    @Column(nullable = false)
    private BigDecimal temperature;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** 决策 010 修订：当前全站使用的 provider；为空时回退下面四个旧字段。 */
    @Column(name = "active_provider_id")
    private UUID activeProviderId;

    /** 当前全站使用的模型（provider 下的某个 model）；为空时回退 provider.model。 */
    @Column(name = "active_model")
    private String activeModel;

    /** 决策 022：最近一次故障转移时间，用于回切冷却判定与界面展示。 */
    @Column(name = "last_failover_at")
    private Instant lastFailoverAt;

    /** 决策 022：从哪条 provider 切过来的（回切目标）。 */
    @Column(name = "failover_from_provider_key", length = 80)
    private String failoverFromProviderKey;

    /** circuit_open / no_credentials / manual 等；空表示从未切换。 */
    @Column(name = "failover_reason", length = 40)
    private String failoverReason;

    protected AiSettingsEntity() {
    }

    public Integer getId() { return id; }
    public String getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public String getApiKeyCipher() { return apiKeyCipher; }
    public BigDecimal getTemperature() { return temperature; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public UUID getActiveProviderId() { return activeProviderId; }
    public String getActiveModel() { return activeModel; }

    public void setProvider(String provider) { this.provider = provider; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setModel(String model) { this.model = model; }
    public void setApiKeyCipher(String apiKeyCipher) { this.apiKeyCipher = apiKeyCipher; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public void setActiveProviderId(UUID activeProviderId) { this.activeProviderId = activeProviderId; }
    public void setActiveModel(String activeModel) { this.activeModel = activeModel; }
    public Instant getLastFailoverAt() { return lastFailoverAt; }
    public void setLastFailoverAt(Instant v) { this.lastFailoverAt = v; }
    public String getFailoverFromProviderKey() { return failoverFromProviderKey; }
    public void setFailoverFromProviderKey(String v) { this.failoverFromProviderKey = v; }
    public String getFailoverReason() { return failoverReason; }
    public void setFailoverReason(String v) { this.failoverReason = v; }
}
