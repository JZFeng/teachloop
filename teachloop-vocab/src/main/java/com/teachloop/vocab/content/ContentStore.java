package com.teachloop.vocab.content;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

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

    /**
     * 批量存在性探测（T572 覆盖率 / FR-240）：返回入参里**已经就绪**的规范化词形子集。
     *
     * <p>与 {@link #get} 同一套命中口径（内容包 → delta，且只认当前 promptVersion），
     * 但只判存在、不返回正文，也不计入 hits/misses 指标。
     */
    default Set<String> existingForms(String language, Collection<String> normalizedForms) {
        Set<String> found = new java.util.LinkedHashSet<>();
        for (String form : normalizedForms) {
            if (form != null && !form.isEmpty() && get(language, form).isPresent()) {
                found.add(form);
            }
        }
        return found;
    }

    /** 内容索引是否可用；false 时覆盖率回 null（界面显示「—」），而不是假的 0%。 */
    default boolean available() {
        return true;
    }

    /**
     * 这条内容的配图地址（2026-10-31）：没有图时返回 empty。
     *
     * <p>入参是 {@code (language, normalizedForm)} 而不是 contentId：词由内容包优先命中时，
     * 包里的 id 是「按词形排序的 1..N 排列」，与图片文件名（delta 的稳定 id）不是同一个 id 空间，
     * 详见 {@link VocabImageStore#url(String, String)}。
     *
     * <p>默认实现返回 empty——**旧实现与测试替身不需要知道图片这回事**，
     * 只有 {@link ContentService} 真的接了 {@link VocabImageStore} 才回地址。
     * 这样「哪些词有图」只有一个属主，读路径（队列 / 会话 / 详情 / 小测 / 词库列表）
     * 都通过 {@link ContentView#resolve} 拿到同一份答案。
     */
    default Optional<String> imageUrl(String language, String normalizedForm) {
        return Optional.empty();
    }
}
