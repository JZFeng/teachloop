package com.teachloop.vocab.books;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.books.BookRepository.BookRow;
import com.teachloop.vocab.books.BookRepository.EntryPage;
import com.teachloop.vocab.books.BookRepository.EntryRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端词书（T573 / T574 / T575 / T578 学生侧 / US-101、US-102、US-104）：列表、推荐、详情、预览、加入。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li>**只返回已发布**（AC-101.1 / FR-236）：草稿与下架书在列表里不出现，按 id 访问一律 404，
 *       不泄露存在性；</li>
 *   <li>**推荐只看端口**（AC-104.3 / FR-250）：本类里没有排序规则，也没有 SQL；</li>
 *   <li>**学生端响应不含价格字段**（AC-105.4 / FR-255）：{@link BookSummary} / {@link BookDetail}
 *       刻意没有 `payMode` / `priceCents` / `currency`，不是靠序列化时裁剪。</li>
 * </ul>
 *
 * <p>加入是一个事务，见 {@link BookJoinService}；本类只做 HTTP 形状。
 */
@RestController
@RequestMapping("/api/vocab/books")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class BookCatalogEndpoint {

    /** AC-101.3：预览固定 50 词一页（上限与之一致，前端不要自己编更大的页）。 */
    private static final int DEFAULT_PREVIEW_SIZE = 50;
    private static final int MAX_PREVIEW_SIZE = 200;
    /** AC-104.1：推荐区 2 本。 */
    private static final int DEFAULT_RECOMMEND_LIMIT = 2;

    private final BookRepository books;
    private final BookCoverageService coverage;
    private final BookJoinService joins;
    private final Ports.BookRecommendation recommendation;

    public BookCatalogEndpoint(BookRepository books, BookCoverageService coverage,
                               BookJoinService joins, Ports.BookRecommendation recommendation) {
        this.books = books;
        this.coverage = coverage;
        this.joins = joins;
        this.recommendation = recommendation;
    }

    /** 列表/推荐/详情的公共形状；**没有**价格字段（AC-105.4）。 */
    public record BookSummary(UUID id, String name, String description, String language, String level,
                              int wordCount, Integer readyCount, boolean joined) {
    }

    public record BookDetail(UUID id, String name, String description, String language, String level,
                             int wordCount, Integer readyCount, boolean joined, int joinedEntries,
                             UUID listId) {
    }

    public record EntryView(long id, String form, int position, boolean ready) {
    }

    public record EntriesPage(List<EntryView> items, long total, int page, int size, int totalWords) {
    }

    /** AC-102.1 四元组：`created + duplicated = total`。 */
    public record JoinResponse(int created, int duplicated, int total, UUID listId) {
    }

    @GetMapping
    public List<BookSummary> list(@RequestAttribute("currentUser") CurrentUser me) {
        List<BookRow> rows = books.listPublished();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, BookCoverageService.Coverage> coverages = coverage.coverageFor(rows);
        Set<UUID> joined = Set.copyOf(books.joinedBookIds(me.id(), rows.stream().map(BookRow::id).toList()));
        List<BookSummary> summaries = new ArrayList<>(rows.size());
        for (BookRow row : rows) {
            summaries.add(summary(row, coverages.get(row.id()), joined.contains(row.id())));
        }
        return summaries;
    }

    /**
     * 推荐（AC-104.3 / AC-104.4）：规则全部来自 {@link Ports.BookRecommendation}；
     * 这里只把 `BookRef` 补成与其他列表同形的 {@link BookSummary}（补简介/语言/级别/是否已加入）。
     */
    @GetMapping("/recommended")
    public List<BookSummary> recommended(@RequestAttribute("currentUser") CurrentUser me,
                                         @RequestParam(defaultValue = "2") int limit) {
        int resolved = limit < 1 ? DEFAULT_RECOMMEND_LIMIT : Math.min(limit, 10);
        List<Ports.BookRef> refs = recommendation.recommended(me.id(), resolved);
        if (refs.isEmpty()) {
            return List.of();
        }
        Map<UUID, BookRow> rows = new HashMap<>();
        for (BookRow row : books.findAllByIds(refs.stream().map(Ports.BookRef::id).toList())) {
            rows.put(row.id(), row);
        }
        Set<UUID> joined = Set.copyOf(books.joinedBookIds(me.id(), rows.keySet()));
        List<BookSummary> summaries = new ArrayList<>(refs.size());
        for (Ports.BookRef ref : refs) {
            BookRow row = rows.get(ref.id());
            if (row == null) {
                continue;
            }
            summaries.add(new BookSummary(row.id(), ref.name(), row.description(), row.language(),
                    row.level(), ref.wordCount(), ref.readyCount(), joined.contains(row.id())));
        }
        return summaries;
    }

    /** AC-101.2：详情 + 覆盖率 +「我是否已加入」+「已加入 N / M」（N = 书里已在我词库的词条数）。 */
    @GetMapping("/{id}")
    public BookDetail detail(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        BookRow book = requirePublished(id);
        BookCoverageService.Coverage cover = coverage.coverage(book);
        UUID listId = joins.copyListId(me.id(), id);
        return new BookDetail(book.id(), book.name(), book.description(), book.language(), book.level(),
                cover.total(), cover.ready(), listId != null, books.entriesInVocab(id, me.id(), book.language()),
                listId);
    }

    /** AC-101.3：预览 50/页（默认值就是 50），每行给「内容就绪 / 生成中」；总数由 `total` 给出。 */
    @GetMapping("/{id}/entries")
    public EntriesPage entries(@PathVariable UUID id,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "50") int size,
                               @RequestAttribute("currentUser") CurrentUser me) {
        BookRow book = requirePublished(id);
        if (page < 1) {
            throw ApiException.badRequest("page must be >= 1");
        }
        if (size < 1 || size > MAX_PREVIEW_SIZE) {
            throw ApiException.badRequest("size must be 1.." + MAX_PREVIEW_SIZE);
        }
        EntryPage pageRows = books.entries(id, (page - 1) * size, size);
        Set<String> ready = coverage.readyForms(book.language(),
                pageRows.items().stream().map(EntryRow::normalizedForm).toList());
        List<EntryView> items = pageRows.items().stream()
                .map(entry -> new EntryView(entry.id(), entry.form(), entry.position(),
                        ready.contains(entry.normalizedForm())))
                .toList();
        return new EntriesPage(items, pageRows.total(), page, size, (int) pageRows.total());
    }

    /** AC-102.1：一次请求完成「入词库 + 副本词单 + 加入记录」，返回四元组。 */
    @PostMapping("/{id}/join")
    public JoinResponse join(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        BookJoinService.JoinResult result = joins.join(me.id(), id);
        return new JoinResponse(result.created(), result.duplicated(), result.total(), result.listId());
    }

    private BookRow requirePublished(UUID id) {
        return books.findPublished(id)
                .orElseThrow(() -> ApiException.notFound("word book not found"));
    }

    private static BookSummary summary(BookRow row, BookCoverageService.Coverage cover, boolean joined) {
        return new BookSummary(row.id(), row.name(), row.description(), row.language(), row.level(),
                cover == null ? 0 : cover.total(), cover == null ? null : cover.ready(), joined);
    }
}
