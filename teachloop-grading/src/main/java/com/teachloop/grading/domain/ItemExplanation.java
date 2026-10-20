package com.teachloop.grading.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * T087：一道题的解析，全作业共享（plan §6.4）。
 *
 * <p>唯一键 {@code (item_id, language, prompt_version)} 是「50 人错同一题只调一次模型」的
 * 数据库保证；写入方用 {@code ON CONFLICT DO NOTHING} 幂等插入，不靠先查后写。
 */
@Entity
@Table(name = "item_explanations")
public class ItemExplanation {

    /** 生成成功、可被学生反馈读取的状态。 */
    public static final String STATE_DONE = "done";

    @Id
    private UUID id;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(nullable = false, length = 16)
    private String language;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @Column(columnDefinition = "text")
    private String text;

    @Column(nullable = false, length = 16)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ItemExplanation() {
    }

    public ItemExplanation(UUID id, UUID itemId, String language, String promptVersion, String text,
                           String state, Instant createdAt) {
        this.id = id;
        this.itemId = itemId;
        this.language = language;
        this.promptVersion = promptVersion;
        this.text = text;
        this.state = state;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getItemId() { return itemId; }
    public String getLanguage() { return language; }
    public String getPromptVersion() { return promptVersion; }
    public String getText() { return text; }
    public String getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
}
