package com.teachloop.vocab.books;

import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.books.BookRepository.BookRow;
import com.teachloop.vocab.books.BookRepository.EntryPage;
import com.teachloop.vocab.books.BookRepository.EntryRow;
import com.teachloop.vocab.importer.DictionaryTextParser;
import com.teachloop.vocab.importer.ImportPreviewEndpoint.CandidateView;
import com.teachloop.vocab.importer.ImportPreviewEndpoint.CleanupView;
import com.teachloop.vocab.importer.ImportPreviewEndpoint.CountsView;
import com.teachloop.vocab.importer.ImportPreviewEndpoint.LineErrorView;
import com.teachloop.vocab.importer.ImportPreviewEndpoint.PendingView;
import com.teachloop.vocab.importer.ImportPreviewEndpoint.PreviewResponse;
import com.teachloop.vocab.importer.ImportPreviewEndpoint.SkippedView;
import com.teachloop.vocab.importer.ImportPreviewEndpoint.SpellingView;
import com.teachloop.vocab.importer.ImportPreviewEndpoint.TokenView;
import com.teachloop.vocab.importer.ParseResult;
import com.teachloop.vocab.spelling.SpellingChecker;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端词书（T570 / T571 / T578 管理侧 / US-100）：CRUD、发布/下架、词条导入与预览、覆盖率、加入人数。
 *
 * <p>权限：整条前缀在 `SecurityConfig` 里按 `/api/admin/**` 收口成 `hasRole("ADMIN")`，
 * 非 ADMIN 得 403（AC-100.1）；这里不重复写角色判断，避免两套口径。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li>**没有物理删除**（AC-100.5 / FR-235）：只有 `published=false` 的下架，学生数据一行不动；
 *       词条删除接口只删书里的词条，不碰任何学生的词库与进度。</li>
 *   <li>**导入复用加词页**（AC-100.6 / FR-241）：解析走同一个 {@link DictionaryTextParser}，
 *       返回体与 `/api/vocab/import/preview` **同形**（直接复用对方的 record，字段不会漂移），
 *       前端因此可以复用同一块预览组件。</li>
 *   <li>**v1 只允许免费书**（AC-105.2）：建书/改书传 `PAID` 一律 400；管理端可见
 *       `payMode/priceCents/currency`，学生端响应不含（AC-105.4）。</li>
 * </ul>
 *
 * <p>审计（FR-245）：发布 / 下架 / 建书 / 改书 / 导入词条 / 删词条都写 {@link AuditPort}，
 * 事件名见各处 {@code book:*}。
 */
@RestController
@RequestMapping("/api/admin/vocab/books")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class AdminBookEndpoint {

    private static final int MAX_NAME = 80;
    private static final int MAX_DESCRIPTION = 300;
    private static final int MAX_SLUG = 64;
    private static final int MAX_LEVEL = 24;
    private static final int MAX_LANGUAGE = 8;
    private static final int MAX_PAGE_SIZE = 200;
    private static final String DEFAULT_LANGUAGE = "en";
    private static final String FREE = "FREE";
    private static final Pattern SLUG_SHAPE = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private final BookRepository books;
    private final BookImportService importService;
    private final BookCoverageService coverage;
    private final DictionaryTextParser parser;
    private final SpellingChecker spelling;
    private final AuditPort audit;
    private final Clock clock;

    public AdminBookEndpoint(BookRepository books, BookImportService importService,
                             BookCoverageService coverage, DictionaryTextParser parser,
                             SpellingChecker spelling, AuditPort audit, Clock clock) {
        this.books = books;
        this.importService = importService;
        this.coverage = coverage;
        this.parser = parser;
        this.spelling = spelling;
        this.audit = audit;
        this.clock = clock;
    }

    /** v1 不接受的字段直接不放进请求体：价格由服务端固定为 `PAID→400`、`0/CNY`（AC-105.1）。 */
    public record CreateRequest(String slug, String name, String description, String language,
                                String level, Integer position, String payMode) {
    }

    public record UpdateRequest(String name, String description, String language, String level,
                                Integer position, Boolean published, String payMode) {
    }

    public record AdminBookView(UUID id, String slug, String name, String description, String language,
                                String level, int position, boolean published, String payMode,
                                int priceCents, String currency, int wordCount, Integer readyCount,
                                long joinCount, Instant updatedAt) {
    }

    /** 预览列表一行：`ready` = 该词形在内容索引里就绪（AC-101.3 的「内容就绪 / 生成中」）。 */
    public record EntryView(long id, String form, int position, boolean ready, String note) {
    }

    public record EntriesPage(List<EntryView> items, long total, int page, int size, int totalWords) {
    }

    public record ImportRequest(String text) {
    }

    public record ImportCommit(BookImportService.Counts counts, List<BookImportService.LineError> errors) {
    }

    public record Affected(int affected) {
    }

    @GetMapping
    public List<AdminBookView> list() {
        List<BookRow> rows = books.listAll();
        Map<UUID, BookCoverageService.Coverage> coverages = coverage.coverageFor(rows);
        Map<UUID, Long> joinCounts = books.joinCounts(rows.stream().map(BookRow::id).toList());
        List<AdminBookView> views = new ArrayList<>(rows.size());
        for (BookRow row : rows) {
            views.add(view(row, coverages, joinCounts));
        }
        return views;
    }

    @GetMapping("/{id}")
    public AdminBookView detail(@PathVariable UUID id) {
        BookRow book = requireBook(id);
        return view(book, Map.of(id, coverage.coverage(book)), Map.of(id, books.joinCount(id)));
    }

    @PostMapping
    @Transactional
    public AdminBookView create(@RequestBody(required = false) CreateRequest request,
                                @RequestAttribute("currentUser") CurrentUser me) {
        if (request == null) {
            throw ApiException.badRequest("request body is required");
        }
        String name = validName(request.name());
        requireFree(request.payMode());
        Instant now = clock.instant();
        String slug = request.slug() == null || request.slug().isBlank()
                ? uniqueSlug(slugBase(name))
                : explicitSlug(request.slug());
        BookRow book = new BookRow(UUID.randomUUID(), slug, name, validDescription(request.description()),
                validLanguage(request.language()), validLevel(request.level()),
                request.position() == null ? 0 : request.position(), false, FREE, 0, "CNY", now, now);
        try {
            books.insert(book);
        } catch (DuplicateKeyException conflict) {
            throw ApiException.conflict("a word book with this slug already exists");
        }
        audit.record(AuditEvent.of(me.id(), "book:create", "vocab_book", book.id().toString()));
        return view(book, Map.of(book.id(), new BookCoverageService.Coverage(0, 0)),
                Map.of(book.id(), 0L));
    }

    /**
     * 改元信息 / 发布 / 下架（AC-100.2 / AC-100.5）。发布与下架都改 `published`，
     * 但审计事件分开记（`book:publish` / `book:archive`），事后能分辨是哪一个动作。
     */
    @PutMapping("/{id}")
    @Transactional
    public AdminBookView update(@PathVariable UUID id, @RequestBody(required = false) UpdateRequest request,
                                @RequestAttribute("currentUser") CurrentUser me) {
        BookRow current = requireBook(id);
        if (request == null) {
            return view(current, Map.of(id, coverage.coverage(current)), Map.of(id, books.joinCount(id)));
        }
        if (request.payMode() != null && !FREE.equalsIgnoreCase(request.payMode())) {
            // AC-105.2：v1 不能创建/切换成付费书
            throw ApiException.badRequest("paid word books are not available: payMode must be FREE");
        }
        String name = request.name() == null ? current.name() : validName(request.name());
        String description = request.description() == null ? current.description()
                : validDescription(request.description());
        String language = request.language() == null ? current.language() : validLanguage(request.language());
        String level = request.level() == null ? current.level() : validLevel(request.level());
        int position = request.position() == null ? current.position() : request.position();
        boolean published = request.published() == null ? current.published() : request.published();
        BookRow updated = new BookRow(current.id(), current.slug(), name, description, language, level,
                position, published, current.payMode(), current.priceCents(), current.currency(),
                current.createdAt(), clock.instant());
        books.updateMeta(updated);
        if (published != current.published()) {
            audit.record(AuditEvent.of(me.id(), published ? "book:publish" : "book:archive",
                    "vocab_book", id.toString()));
        } else {
            audit.record(AuditEvent.of(me.id(), "book:update", "vocab_book", id.toString()));
        }
        return view(updated, Map.of(id, coverage.coverage(updated)), Map.of(id, books.joinCount(id)));
    }

    /** 预览：与「加词」页同一个解析器、同一个返回形状（AC-100.6），计数换成「这本书里有没有」。 */
    @PostMapping("/{id}/entries/preview")
    public PreviewResponse previewEntries(@PathVariable UUID id,
                                          @RequestBody(required = false) ImportRequest request) {
        BookRow book = requireBook(id);
        String text = request == null ? "" : request.text();
        ParseResult result = parser.parse(text, book.language());
        BookImportService.PreviewResult preview = importService.preview(id, book.language(), text);
        List<String> distinct = result.distinctNormalized();
        boolean spellingChecked = spelling.available();
        List<SpellingView> spellingViews = spellingChecked
                ? distinct.stream().map(word -> {
                    SpellingChecker.SpellingResult check = spelling.check(word);
                    return new SpellingView(check.word(), check.state(), check.band(),
                            check.candidates().stream()
                                    .map(candidate -> new CandidateView(
                                            candidate.form(), candidate.distance(), candidate.band()))
                                    .toList());
                }).toList()
                : List.of();
        return new PreviewResponse(
                result.lineCount(),
                result.tokens().stream().map(token -> new TokenView(token.raw(), token.lineNumber())).toList(),
                distinct,
                spellingChecked,
                spellingViews,
                new CountsView(preview.counts().total(), preview.counts().created(),
                        preview.counts().duplicated(), preview.counts().failed()),
                preview.errors().stream()
                        .map(error -> new LineErrorView(error.line(), error.raw(), error.reason()))
                        .toList(),
                result.skipped().stream()
                        .map(line -> new SkippedView(line.lineNumber(), line.text(), line.reason()))
                        .toList(),
                result.pending().stream()
                        .map(line -> new PendingView(line.lineNumber(), line.text(), line.reason()))
                        .toList(),
                result.cleanups().stream()
                        .map(line -> new CleanupView(line.lineNumber(), line.rule(), line.before(), line.after()))
                        .toList());
    }

    /** 提交：写 `vocab_book_entries`（不写学生词库、不入队内容生成）。 */
    @PostMapping("/{id}/entries")
    @Transactional
    public ImportCommit importEntries(@PathVariable UUID id,
                                      @RequestBody(required = false) ImportRequest request,
                                      @RequestAttribute("currentUser") CurrentUser me) {
        BookRow book = requireBook(id);
        String text = request == null ? "" : request.text();
        BookImportService.CommitResult result = importService.commit(id, book.language(), text);
        audit.record(new AuditEvent(me.id(), "book:entries.import", "vocab_book", id.toString(),
                Map.of("created", result.counts().created(), "duplicated", result.counts().duplicated(),
                        "failed", result.counts().failed())));
        return new ImportCommit(result.counts(), result.errors());
    }

    @GetMapping("/{id}/entries")
    public EntriesPage entries(@PathVariable UUID id,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "50") int size) {
        BookRow book = requireBook(id);
        if (page < 1) {
            throw ApiException.badRequest("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("size must be 1.." + MAX_PAGE_SIZE);
        }
        EntryPage pageRows = books.entries(id, (page - 1) * size, size);
        Set<String> ready = coverage.readyForms(book.language(),
                pageRows.items().stream().map(EntryRow::normalizedForm).toList());
        List<EntryView> items = pageRows.items().stream()
                .map(entry -> new EntryView(entry.id(), entry.form(), entry.position(),
                        ready.contains(entry.normalizedForm()), entry.note()))
                .toList();
        return new EntriesPage(items, pageRows.total(), page, size, (int) pageRows.total());
    }

    /** 删单条词条（FR-245 审计）；不删书、不碰学生数据。 */
    @DeleteMapping("/{id}/entries/{entryId}")
    @Transactional
    public Affected deleteEntry(@PathVariable UUID id, @PathVariable long entryId,
                                @RequestAttribute("currentUser") CurrentUser me) {
        requireBook(id);
        int affected = books.deleteEntry(id, entryId);
        if (affected == 0) {
            throw ApiException.notFound("word book entry not found");
        }
        audit.record(AuditEvent.of(me.id(), "book:entry.delete", "vocab_book_entry",
                id + "/" + entryId));
        return new Affected(affected);
    }

    private BookRow requireBook(UUID id) {
        return books.find(id).orElseThrow(() -> ApiException.notFound("word book not found"));
    }

    private AdminBookView view(BookRow book, Map<UUID, BookCoverageService.Coverage> coverages,
                               Map<UUID, Long> joinCounts) {
        BookCoverageService.Coverage cover = coverages.get(book.id());
        return new AdminBookView(book.id(), book.slug(), book.name(), book.description(), book.language(),
                book.level(), book.position(), book.published(), book.payMode(), book.priceCents(),
                book.currency(), cover == null ? 0 : cover.total(), cover == null ? null : cover.ready(),
                joinCounts.getOrDefault(book.id(), 0L), book.updatedAt());
    }

    /** AC-105.2：v1 建书只允许 FREE；带 PAID 直接 400，不靠前端隐藏。 */
    private static void requireFree(String payMode) {
        if (payMode != null && !payMode.isBlank() && !FREE.equalsIgnoreCase(payMode.strip())) {
            throw ApiException.badRequest("paid word books are not available: payMode must be FREE");
        }
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > MAX_NAME) {
            throw ApiException.badRequest("name is required (1-" + MAX_NAME + " chars)");
        }
        return name;
    }

    private static String validDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String description = raw.strip();
        if (description.isEmpty()) {
            return null;
        }
        if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION) {
            throw ApiException.badRequest("description must be <=" + MAX_DESCRIPTION + " chars");
        }
        return description;
    }

    private static String validLanguage(String raw) {
        String language = raw == null || raw.isBlank() ? DEFAULT_LANGUAGE : raw.strip().toLowerCase(Locale.ROOT);
        if (language.length() > MAX_LANGUAGE) {
            throw ApiException.badRequest("language must be <=" + MAX_LANGUAGE + " chars");
        }
        return language;
    }

    private static String validLevel(String raw) {
        if (raw == null) {
            return null;
        }
        String level = raw.strip();
        if (level.isEmpty()) {
            return null;
        }
        if (level.length() > MAX_LEVEL) {
            throw ApiException.badRequest("level must be <=" + MAX_LEVEL + " chars");
        }
        return level;
    }

    /** 显式传 slug：形状与唯一性都当场校验，不静默改名。 */
    private String explicitSlug(String raw) {
        String slug = raw.strip().toLowerCase(Locale.ROOT);
        if (slug.length() > MAX_SLUG || !SLUG_SHAPE.matcher(slug).matches()) {
            throw ApiException.badRequest("slug must match " + SLUG_SHAPE.pattern()
                    + " and be <=" + MAX_SLUG + " chars");
        }
        if (books.slugTaken(slug, null)) {
            throw ApiException.conflict("a word book with this slug already exists");
        }
        return slug;
    }

    /**
     * 不传 slug 时从书名生成：中文书名转不出 ascii 就退化成 `book`，再靠后缀保证唯一
     * （slug 是 varchar(64) not null unique，不能空）。
     */
    private String uniqueSlug(String base) {
        for (int attempt = 1; attempt <= 100; attempt++) {
            String candidate = attempt == 1 ? base : base + "-" + attempt;
            if (candidate.length() > MAX_SLUG) {
                continue;
            }
            if (!books.slugTaken(candidate, null)) {
                return candidate;
            }
        }
        throw ApiException.conflict("cannot allocate a slug for this book");
    }

    /** 书名 → slug：只留 a-z0-9 与 '-'，压缩连续分隔符；全被过滤掉时用 `book`。 */
    static String slugBase(String name) {
        String base = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return base.isEmpty() ? "book" : (base.length() > 48 ? base.substring(0, 48) : base);
    }
}
