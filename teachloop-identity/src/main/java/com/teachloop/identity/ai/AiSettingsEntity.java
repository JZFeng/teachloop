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

    public void setProvider(String provider) { this.provider = provider; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setModel(String model) { this.model = model; }
    public void setApiKeyCipher(String apiKeyCipher) { this.apiKeyCipher = apiKeyCipher; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
