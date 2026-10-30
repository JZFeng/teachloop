package com.teachloop.vocab.books;

import com.teachloop.vocab.books.BookRepository.BookRow;
import com.teachloop.vocab.content.ContentStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 词书覆盖率（T572 / FR-240）：`ready = 命中内容索引的词条数`，`total = 词条数`，**请求时实时算、不落库**。
 *
 * <p>管理端（AC-100.4）与学生端（AC-101.2 / AC-103.3）共用这一个服务，两个界面就不会出现两套口径。
 * 计算按「书的语言」分组后调 {@link ContentStore#existingForms} 一次批量问内容索引——
 * 逐词问在 2000 词的书上就是 2000 次 IO，而覆盖率是每次进详情页都要算的东西。
 *
 * <p>内容索引不可用时 `ready` 为 {@code null}（界面显示「—」），不是 0%：0% 会被读成
 * 「这本书还没做内容」，而实际是「我们查不到」（plan §6）。
 */
@Service
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true", matchIfMissing = true)
public class BookCoverageService {

    private final BookRepository books;
    private final ContentStore content;

    public BookCoverageService(BookRepository books, ContentStore content) {
        this.books = books;
        this.content = content;
    }

    /** `ready` 为 null = 内容索引不可用；`total` 恒为真实词条数（0 就是 0，不抛异常）。 */
    public record Coverage(Integer ready, int total) {
    }

    public Coverage coverage(BookRow book) {
        return coverageFor(List.of(book)).getOrDefault(book.id(), new Coverage(null, 0));
    }

    /**
     * 单个词形是否已就绪（预览列表逐行标「内容就绪 / 生成中」用，AC-101.3）。
     *
     * <p>与覆盖率同一口径（{@link ContentStore#existingForms}）；内容索引不可用时回空集，
     * 界面一律显示「生成中」——不能把「查不到」说成「已就绪」。
     */
    public Set<String> readyForms(String language, Collection<String> normalizedForms) {
        if (normalizedForms == null || normalizedForms.isEmpty() || !content.available()) {
            return Set.of();
        }
        String lang = language == null || language.isBlank() ? "en" : language;
        return content.existingForms(lang, normalizedForms);
    }

    /**
     * 批量覆盖率：一次取全部词条、按语言合并词形后只问内容索引一次/语言。
     *
     * <p>没有词条的书直接回 {@code (ready=0, total=0)}——AC-100.4 要求「无书条目时返回 0/0 而不是异常」。
     */
    public Map<UUID, Coverage> coverageFor(Collection<BookRow> bookList) {
        Map<UUID, Coverage> result = new HashMap<>();
        if (bookList == null || bookList.isEmpty()) {
            return result;
        }
        List<UUID> ids = new ArrayList<>();
        for (BookRow book : bookList) {
            ids.add(book.id());
        }
        Map<UUID, List<String>> formsByBook = books.normalizedFormsByBook(ids);
        boolean available = content.available();
        if (!available) {
            for (BookRow book : bookList) {
                result.put(book.id(), new Coverage(null,
                        formsByBook.getOrDefault(book.id(), List.of()).size()));
            }
            return result;
        }
        // 语言 → 该书全部词形的并集（同一本书里 normalized_form 唯一，跨书重复很常见，去重后只查一次）
        Map<String, Set<String>> formsByLanguage = new HashMap<>();
        for (BookRow book : bookList) {
            Set<String> forms = formsByLanguage.computeIfAbsent(book.language(),
                    key -> new LinkedHashSet<>());
            forms.addAll(formsByBook.getOrDefault(book.id(), List.of()));
        }
        Map<String, Set<String>> readyByLanguage = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : formsByLanguage.entrySet()) {
            readyByLanguage.put(entry.getKey(), content.existingForms(entry.getKey(), entry.getValue()));
        }
        for (BookRow book : bookList) {
            List<String> forms = formsByBook.getOrDefault(book.id(), List.of());
            Set<String> ready = readyByLanguage.getOrDefault(book.language(), Set.of());
            int readyCount = 0;
            for (String form : forms) {
                if (ready.contains(form)) {
                    readyCount++;
                }
            }
            result.put(book.id(), new Coverage(readyCount, forms.size()));
        }
        return result;
    }
}
