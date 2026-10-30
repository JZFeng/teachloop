package com.teachloop.vocab.content;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

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

    /**
     * 批量「有没有内容」（T572 覆盖率）：只判存在性，不取正文。
     *
     * <p>默认实现逐条走 {@link #get}（对内容包就是一次 positional read + JSON 解析）；
     * delta 这类每词一条 SQL 的源覆盖它，换成一次 {@code in (...)} 查询（见
     * {@link DeltaContentStore#existingForms}）。覆盖率只关心命中数，因此这里**不计** hits/misses
     * 指标——那是「内容被真正取用」的口径，混进覆盖率会让内容命中率失真。
     */
    default Set<String> existingForms(String language, Collection<String> normalizedForms) {
        Set<String> found = new LinkedHashSet<>();
        for (String form : normalizedForms) {
            if (form != null && !form.isEmpty() && get(language, form).isPresent()) {
                found.add(form);
            }
        }
        return found;
    }

    /**
     * 这一层当前能不能用。覆盖率据此区分「0% 就绪」与「内容索引不可用」（plan §6：不可用时显示
     * 「—」而不是 0%）。内容包缺失 / delta 打不开时返回 false。
     */
    default boolean available() {
        return true;
    }
}
