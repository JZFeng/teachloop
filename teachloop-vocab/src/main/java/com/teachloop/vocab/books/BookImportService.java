package com.teachloop.vocab.books;

import com.teachloop.vocab.books.BookRepository.NewEntry;
import com.teachloop.vocab.importer.DictionaryTextParser;
import com.teachloop.vocab.importer.ParseResult;
import com.teachloop.vocab.word.WordNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 词条导入（T571 / AC-100.3 / AC-100.6 / FR-241）：**复用「加词」页的解析器**，只换提交目标。
 *
 * <p>清洗与跳过口径 100% 来自 {@link DictionaryTextParser}（同一个实例、同一套规则、同一份
 * {@code cleanups} / {@code skipped} 输出），去重口径来自 {@link WordNormalizer}——与
 * {@code WordImportService} 逐行对齐，只有两处不同：
 * <ul>
 *   <li>「已存在」的判据是**这本书里有没有这个词形**，不是我的词库；</li>
 *   <li>提交写 `vocab_book_entries`，不写 `vocab_words`、不建来源、不入队内容生成。</li>
 * </ul>
 * 用户 2026-10-30 的验收要求就是「同一段文本在加词页与书导入页给出相同的清洗与跳过结果」，
 * 所以这里刻意不复制任何清洗逻辑，只复制两个计数循环的形状。
 *
 * <p>非法行（空正面、超过 64 code point）**不写库**，只回行号与原因（宪法：静默丢弃即缺陷）。
 */
@Service
public class BookImportService {

    private static final int CHUNK = 500;

    private final DictionaryTextParser parser;
    private final BookRepository books;

    public BookImportService(DictionaryTextParser parser, BookRepository books) {
        this.parser = parser;
        this.books = books;
    }

    public record LineError(int line, String raw, String reason) {
    }

    /** 三项之和 = total（与「加词」页的 Counts 同名同义，前端复用同一块预览 UI）。 */
    public record Counts(int total, int created, int duplicated, int failed) {
    }

    public record PreviewResult(Counts counts, List<LineError> errors) {
    }

    public record CommitResult(Counts counts, List<LineError> errors) {
    }

    /**
     * 新建词书时的草稿预览（2026-10-30 用户要求「新建时就把词条塞进去，别分两步」）：
     * 书还没建，所以没有「已在书中」，`duplicated` 只剩批内重复；解析、清洗、跳过与拼写
     * 与 {@link #preview(UUID, String, String)} 逐行同源，界面因此还是同一块预览组件。
     */
    public PreviewResult previewDraft(String language, String text) {
        return preview(null, language, text);
    }

    /**
     * 只查库不写库：与 {@code WordImportService#preview} 同一份扫描逻辑。
     *
     * @param bookId 目标书；{@code null} 表示书还没建（新建面板的草稿预览），按空书算
     */
    public PreviewResult preview(UUID bookId, String language, String text) {
        ParseResult parsed = parser.parse(text, language);
        List<LineError> errors = new ArrayList<>();
        Map<String, String> unique = new LinkedHashMap<>();
        int total = 0;
        int failed = 0;
        int inFileDuplicates = 0;
        for (ParseResult.ParsedToken token : parsed.tokens()) {
            total++;
            if (!WordNormalizer.valid(token.raw())) {
                failed++;
                errors.add(new LineError(token.lineNumber(), token.raw(), "正面为空或超过 64 字符"));
                continue;
            }
            String normalized = WordNormalizer.normalized(token.raw());
            if (unique.putIfAbsent(normalized, token.raw()) != null) {
                inFileDuplicates++;
            }
        }
        // 只查这本书里有没有（不是我的词库）：SQL 只回入参里命中的词形，因此 size 不会超过 unique。
        // bookId 为 null = 书还没建（草稿预览），此时「已在书中」恒为空集，不查库。
        Set<String> existing = bookId == null ? Set.of() : books.existingNormalized(bookId, unique.keySet());
        int created = unique.size() - existing.size();
        int duplicated = inFileDuplicates + existing.size();
        return new PreviewResult(new Counts(total, created, duplicated, failed), errors);
    }

    /**
     * 提交：解析 → 去重 → 写 `vocab_book_entries`（同书 `normalized_form` 唯一）。
     *
     * <p>同名并发/重复导入由唯一键兜底（`on conflict do nothing`），计数以真实写入行为准。
     */
    @Transactional
    public CommitResult commit(UUID bookId, String language, String text) {
        ParseResult parsed = parser.parse(text, language);
        List<LineError> errors = new ArrayList<>();
        Map<String, String> unique = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        int total = 0;
        int failed = 0;
        int inFileDuplicates = 0;
        for (ParseResult.ParsedToken token : parsed.tokens()) {
            total++;
            if (!WordNormalizer.valid(token.raw())) {
                failed++;
                errors.add(new LineError(token.lineNumber(), token.raw(), "正面为空或超过 64 字符"));
                continue;
            }
            String normalized = WordNormalizer.normalized(token.raw());
            String previous = unique.putIfAbsent(normalized, token.raw());
            if (previous != null) {
                inFileDuplicates++;
            } else {
                order.add(normalized);
            }
        }
        Set<String> existing = books.existingNormalized(bookId, unique.keySet());
        int position = books.maxEntryPosition(bookId);
        List<NewEntry> pending = new ArrayList<>();
        for (String normalized : order) {
            if (existing.contains(normalized)) {
                continue;
            }
            String raw = unique.get(normalized);
            pending.add(new NewEntry(WordNormalizer.display(raw), normalized, ++position, null));
        }
        int created = 0;
        for (int start = 0; start < pending.size(); start += CHUNK) {
            List<NewEntry> chunk = pending.subList(start, Math.min(start + CHUNK, pending.size()));
            created += books.insertEntries(bookId, chunk);
        }
        // 并发下 on conflict 会少写几行：以真实写入数为准，差值计入 duplicated，三项之和仍等于 total
        int duplicated = total - failed - created;
        return new CommitResult(new Counts(total, created, duplicated, failed), errors);
    }
}
