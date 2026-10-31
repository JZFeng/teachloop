package com.teachloop.vocab.content;

import com.teachloop.vocab.word.WordNormalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 内容解析链（decisions/017 / plan §4.1.2）：只读内容包 → SQLite delta → miss。
 *
 * <p>只读源通过 {@link ContentReader} 注入，顺序由 {@code @Order} 决定（包 0、delta 100）。
 * 只返回与 {@code teachloop.vocab.content.prompt-version} 一致的内容；换提示词 = 出新包 +
 * 升配置，旧 delta 不再参与命中（plan §4.1.1）。
 *
 * <p>写只落 delta：AI 生成成功、发词快照、存量回填都走 {@link #put}。
 */
@Service
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true", matchIfMissing = true)
public class ContentService implements ContentStore {

    private static final Logger log = LoggerFactory.getLogger(ContentService.class);

    private final List<ContentReader> readers;
    private final DeltaContentStore delta;
    private final String promptVersion;
    /** 配图（2026-10-31）；测试与「只读内容」的旧装配里可以是 null，等价于「没有配图」。 */
    private final VocabImageStore images;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    @org.springframework.beans.factory.annotation.Autowired
    public ContentService(List<ContentReader> readers, DeltaContentStore delta,
                          VocabImageStore images,
                          @Value("${teachloop.vocab.content.prompt-version:vocab-content-v2.3}")
                          String promptVersion) {
        this.readers = readers;
        this.delta = delta;
        this.images = images;
        this.promptVersion = promptVersion;
    }

    /** 不带配图的装配（单元测试 / 只要内容不要图）：等价于 {@code teachloop.vocab.images.enabled=false}。 */
    public ContentService(List<ContentReader> readers, DeltaContentStore delta, String promptVersion) {
        this(readers, delta, null, promptVersion);
    }

    /**
     * 配图地址（2026-10-31）：只有真的存在这张 WebP 才回地址。
     *
     * <p>存在性检查在这里做、不在前端做：前端拿不到「图在不在」的信息，
     * 若后端无条件给 URL，无图的词每次都会打出一个 404 并闪一下破图。
     *
     * <p>入参是词形不是 contentId，原因见 {@link VocabImageStore#url(String, String)}：
     * 读路径命中的可能是内容包（id 是 1..N 的排列），与图片文件名（delta 稳定 id）不是同一空间。
     */
    @Override
    public Optional<String> imageUrl(String language, String normalizedForm) {
        return images == null ? Optional.empty() : images.url(language, normalizedForm);
    }

    @Override
    public Optional<StoredContent> get(String language, String form) {
        String normalized = WordNormalizer.normalized(form);
        if (normalized.isEmpty()) {
            misses.increment();
            return Optional.empty();
        }
        String lang = language == null || language.isBlank() ? "en" : language;
        for (ContentReader reader : readers) {
            Optional<StoredContent> found = reader.get(lang, normalized);
            if (found.isPresent() && promptVersion.equals(found.get().promptVersion())) {
                hits.increment();
                return found;
            }
        }
        misses.increment();
        return Optional.empty();
    }

    @Override
    public Optional<StoredContent> getById(long contentId) {
        if (contentId <= 0) {
            misses.increment();
            return Optional.empty();
        }
        for (ContentReader reader : readers) {
            Optional<StoredContent> found = reader.getById(contentId);
            if (found.isPresent() && promptVersion.equals(found.get().promptVersion())) {
                hits.increment();
                return found;
            }
        }
        misses.increment();
        return Optional.empty();
    }

    @Override
    public long put(String language, String form, String contentJson, String source) {
        String normalized = WordNormalizer.normalized(form);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("form is required");
        }
        String lang = language == null || language.isBlank() ? "en" : language;
        long id = delta.put(lang, normalized, contentJson, source);
        log.debug("内容写入 delta id={} lang={} form={} source={}", id, lang, normalized, source);
        return id;
    }

    @Override
    public ContentStats stats() {
        ContentStats deltaStats = delta.stats();
        return new ContentStats(hits.sum(), misses.sum(), deltaStats.puts(), deltaStats.failures());
    }

    /**
     * 批量存在性探测（T572）：按 reader 顺序逐层问，命中的从待查集合里去掉，
     * 后面的层不必再查——与 {@link #get} 的优先级完全一致，只是不取正文、不计指标。
     */
    @Override
    public Set<String> existingForms(String language, Collection<String> normalizedForms) {
        String lang = language == null || language.isBlank() ? "en" : language;
        Set<String> pending = new LinkedHashSet<>();
        for (String form : normalizedForms) {
            String normalized = WordNormalizer.normalized(form);
            if (!normalized.isEmpty()) {
                pending.add(normalized);
            }
        }
        Set<String> found = new LinkedHashSet<>();
        for (ContentReader reader : readers) {
            if (pending.isEmpty()) {
                break;
            }
            Set<String> layerHits = reader.existingForms(lang, pending);
            found.addAll(layerHits);
            pending.removeAll(layerHits);
        }
        return found;
    }

    /** 任一层可用即算可用：只有「包与 delta 都不可用」时覆盖率才回 null。 */
    @Override
    public boolean available() {
        return readers.stream().anyMatch(ContentReader::available);
    }
}
