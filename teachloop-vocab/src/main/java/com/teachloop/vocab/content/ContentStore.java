package com.teachloop.vocab.content;

import java.util.Optional;

/**
 * 全局词内容端口（decisions/017 / plan §4.1.2）：vocab 模块内使用，不跨模块。
 *
 * <p>同一 {@code (language, normalizedForm)} 只生成一次；查找顺序固定为
 * 只读内容包 → SQLite delta → miss（由 {@link ContentService} 负责）。
 * 读路径要按 {@code vocab_words.content_id} 取内容时用 {@link #getById(long)}。
 */
public interface ContentStore {

    /** 按语言 + 词形查内容；词形会在内部规范化。 */
    Optional<StoredContent> get(String language, String form);

    /** 按 {@code vocab_words.content_id} 查内容；包内 id 与 delta id 分区间。 */
    Optional<StoredContent> getById(long contentId);

    /**
     * 写 delta（AI 生成成功、发词快照、存量回填都走这里）。
     *
     * @return delta 的 content_id（从 1_000_000_000 起）
     */
    long put(String language, String form, String contentJson, String source);

    ContentStats stats();
}
