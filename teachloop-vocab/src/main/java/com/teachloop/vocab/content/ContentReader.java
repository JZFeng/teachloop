package com.teachloop.vocab.content;

import java.util.Optional;

/**
 * 只读内容源（decisions/017 / plan §4.1.2）：只读内容包、SQLite delta 都实现它，
 * {@link ContentService} 按 {@code @Order} 依次查找。
 *
 * <p>入参的 {@code normalizedForm} 必须已经由 {@link com.teachloop.vocab.word.WordNormalizer#normalized}
 * 规范化；调用方一般直接用 {@link ContentStore}，不需要关心这一层。
 */
public interface ContentReader {

    Optional<StoredContent> get(String language, String normalizedForm);

    Optional<StoredContent> getById(long contentId);

    /** 指标/日志里的层名，例如 {@code pack}、{@code delta}。 */
    String layer();
}
