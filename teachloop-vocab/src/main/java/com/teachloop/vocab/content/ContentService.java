package com.teachloop.vocab.content;

import com.teachloop.vocab.word.WordNormalizer;
import java.util.List;
import java.util.Optional;
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
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public ContentService(List<ContentReader> readers, DeltaContentStore delta,
                          @Value("${teachloop.vocab.content.prompt-version:vocab-content-v2.3}")
                          String promptVersion) {
        this.readers = readers;
        this.delta = delta;
        this.promptVersion = promptVersion;
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
}
