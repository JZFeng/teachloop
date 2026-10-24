package com.teachloop.identity.ai;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 全局 AI provider（决策 010 修订）：管理员维护，教师只在下拉里选当前模型。 */
@Entity
@Table(name = "ai_providers")
public class AiProviderEntity {

    @Id
    private UUID id;

    @Column(name = "provider_key", nullable = false, unique = true)
    private String providerKey;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    /** v1 只允许 openai；字段保留给 v2 的 anthropic。 */
    @Column(nullable = false)
    private String protocol;

    @Column(name = "api_key_cipher", columnDefinition = "text")
    private String apiKeyCipher;

    /** 兼容旧行/旧接口：第一个模型。新代码读 modelsJson。 */
    @Column(nullable = false)
    private String model;

    /** provider 可用模型列表（JSON 数组）；一个 provider 可以保存多个模型。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "models", columnDefinition = "jsonb", nullable = false)
    private String modelsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected AiProviderEntity() {
    }

    public AiProviderEntity(UUID id, String providerKey, String displayName, String baseUrl,
                            String protocol, String apiKeyCipher, String model,
                            Instant createdAt, Instant updatedAt, UUID updatedBy) {
        this.id = id;
        this.providerKey = providerKey;
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.protocol = protocol;
        this.apiKeyCipher = apiKeyCipher;
        this.model = model;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public UUID getId() { return id; }
    public String getProviderKey() { return providerKey; }
    public String getDisplayName() { return displayName; }
    public String getBaseUrl() { return baseUrl; }
    public String getProtocol() { return protocol; }
    public String getApiKeyCipher() { return apiKeyCipher; }
    public String getModel() { return model; }
    public String getModelsJson() { return modelsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }

    public void setProviderKey(String v) { this.providerKey = v; }
    public void setDisplayName(String v) { this.displayName = v; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public void setProtocol(String v) { this.protocol = v; }
    public void setApiKeyCipher(String v) { this.apiKeyCipher = v; }
    public void setModel(String v) { this.model = v; }
    public void setModelsJson(String v) { this.modelsJson = v; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public void setUpdatedBy(UUID v) { this.updatedBy = v; }
}
