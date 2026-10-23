package com.teachloop.identity.ai;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 教师/管理员个人 AI 配置；主键就是 user_id，密钥列只存密文。 */
@Entity
@Table(name = "user_ai_settings")
public class UserAiSettingsEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String provider;

    /** 决策 010：教师给这个自定义 provider 起的显示名，例如“办公室 Ollama”。 */
    @Column(name = "display_name")
    private String displayName;

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

    protected UserAiSettingsEntity() {
    }

    public UserAiSettingsEntity(UUID userId, String provider, String displayName, String baseUrl, String model,
                                String apiKeyCipher, BigDecimal temperature, Instant updatedAt, UUID updatedBy) {
        this.userId = userId;
        this.provider = provider;
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKeyCipher = apiKeyCipher;
        this.temperature = temperature;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public UUID getUserId() { return userId; }
    public String getProvider() { return provider; }
    public String getDisplayName() { return displayName; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public String getApiKeyCipher() { return apiKeyCipher; }
    public BigDecimal getTemperature() { return temperature; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }

    public void setProvider(String v) { this.provider = v; }
    public void setDisplayName(String v) { this.displayName = v; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public void setModel(String v) { this.model = v; }
    public void setApiKeyCipher(String v) { this.apiKeyCipher = v; }
    public void setTemperature(BigDecimal v) { this.temperature = v; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public void setUpdatedBy(UUID v) { this.updatedBy = v; }
}
