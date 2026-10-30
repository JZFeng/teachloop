package com.teachloop.vocab.books;

import com.teachloop.shared.error.ApiException;
import com.teachloop.vocab.books.BookRepository.BookRow;
import com.teachloop.vocab.books.BookRepository.EntryRow;
import com.teachloop.vocab.lists.VocabularyListRepository;
import com.teachloop.vocab.lexicon.WordFamily;
import com.teachloop.vocab.lexicon.WordFamilyIndex;
import com.teachloop.vocab.word.VocabWordRepository;
import com.teachloop.vocab.word.WordNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 加入词书（T575 / US-102 / AC-102.1–102.5、AC-103.1）：一个事务里做完五件事。
 *
 * <ol>
 *   <li>校验：书存在且**已发布**（未发布一律 404，不泄露存在性）；`pay_mode` 必须是 FREE
 *       （付费书 409，FR-254）；首次加入前预检词单上限，避免「词进了库、副本没建」。</li>
 *   <li>入词库：按 `(user_id, language, normalized_form)` 去重，每批 ≤500 行
 *       （{@link VocabWordRepository#insertBatch}）。已存在的词**一个字都不改**——软删的只恢复可见性，
 *       进度表 `vocab_progress` 不碰（AC-102.3）。</li>
 *   <li>只追加来源：逐词写 `kind='BOOK'` 的来源行（AC-102.3）。</li>
 *   <li>建/复用副本词单：按 `(user_id, book_id)` 找；不存在就建，**同名加后缀**（AC-102.5）。</li>
 *   <li>`vocab_book_joins` upsert（保留首次 joined_at），**不调用** `enqueueNewWords`
 *       （FR-239 / AC-103.1：加入一本 500 词的书不会产生 500 条生成任务）。</li>
 * </ol>
 *
 * <p>幂等（AC-102.2）：第二次调用 `created=0`、`duplicated=total`，副本词单与 joins 都复用。
 *
 * <p>边界：书是全局只读的，进度永远落在学生自己的词条上——这里没有一处 SQL 写 `vocab_books`
 * 或 `vocab_book_entries`。
 */
@Service
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true", matchIfMissing = true)
public class BookJoinService {

    /** FR-237：单批写库行数上限。 */
    private static final int CHUNK = 500;
    /** 副本词单名最大长度（`vocab_lists.name` 是 varchar(80)，按 code point 截断）。 */
    private static final int MAX_LIST_NAME = 80;
    /** 同名后缀最多试到 "name (100)"，再试下去就是数据本身有问题。 */
    private static final int MAX_SUFFIX_ATTEMPTS = 100;

    private static final Logger log = LoggerFactory.getLogger(BookJoinService.class);

    private final BookRepository books;
    private final VocabWordRepository words;
    private final VocabularyListRepository lists;
    private final WordFamilyIndex lexicon;
    private final Clock clock;
    private final int maxListsPerStudent;

    public BookJoinService(BookRepository books, VocabWordRepository words,
                           VocabularyListRepository lists, WordFamilyIndex lexicon, Clock clock,
                           @Value("${teachloop.vocab.lists.max-per-student:100}")
                           int maxListsPerStudent) {
        this.books = books;
        this.words = words;
        this.lists = lists;
        this.lexicon = lexicon;
        this.clock = clock;
        this.maxListsPerStudent = Math.max(1, maxListsPerStudent);
    }

    /** AC-102.1：四元组；`total` = 书词数（= created + duplicated）。 */
    public record JoinResult(int created, int duplicated, int total, UUID listId) {
    }

    /** 我在这本书上的副本词单 id（未加入为 null）；详情页据此把按钮换成「继续背」。 */
    public UUID copyListId(UUID userId, UUID bookId) {
        return lists.findCopyListId(userId, bookId).orElse(null);
    }

    @Transactional(timeout = 30)
    public JoinResult join(UUID userId, UUID bookId) {
        BookRow book = books.findPublished(bookId)
                .orElseThrow(() -> ApiException.notFound("word book not found"));
        if (!"FREE".equals(book.payMode())) {
            throw ApiException.conflict("paid word books are not available yet");
        }
        List<EntryRow> entries = books.allEntries(bookId);
        Instant now = clock.instant();
        UUID listId = lists.findCopyListId(userId, bookId).orElse(null);
        if (listId == null) {
            if (lists.countActive(userId) >= maxListsPerStudent) {
                // 预检放在任何写入之前：达到上限时整次加入拒绝，不留「词进了库但没副本」的半成品。
                throw ApiException.conflict("list limit reached: at most " + maxListsPerStudent
                        + " lists per student");
            }
            // 副本词单先建：名字冲突/并发唯一键冲突都在任何词条写入之前暴露；空书也能回 listId
            listId = createCopyList(userId, book, now);
        }
        String language = book.language() == null || book.language().isBlank() ? "en" : book.language();
        String label = truncate(book.name(), 80);
        int created = 0;
        int listAdded = 0;
        int listDuplicated = 0;
        int position = lists.maxItemPosition(listId);
        for (int start = 0; start < entries.size(); start += CHUNK) {
            List<EntryRow> chunk = entries.subList(start, Math.min(start + CHUNK, entries.size()));
            List<UUID> wordIds = new ArrayList<>(chunk.size());
            Map<String, VocabWordRepository.ExistingWord> existing =
                    words.findByNormalized(userId, language, chunk.stream().map(EntryRow::normalizedForm).toList());
            List<VocabWordRepository.NewWord> fresh = new ArrayList<>();
            List<Integer> freshSlots = new ArrayList<>();
            for (EntryRow entry : chunk) {
                VocabWordRepository.ExistingWord found = existing.get(entry.normalizedForm());
                if (found != null) {
                    if (found.deleted()) {
                        // 软删的词恢复可见性，进度原样保留（与「导入」同一口径，AC-55.3）
                        words.restore(found.id());
                    }
                    wordIds.add(found.id());
                } else {
                    String display = WordNormalizer.display(entry.form());
                    WordFamily family = lexicon.lookup(display).orElse(null);
                    fresh.add(new VocabWordRepository.NewWord(display, entry.normalizedForm(),
                            family == null ? null : family.familyId(),
                            family == null ? null : family.band()));
                    freshSlots.add(wordIds.size());
                    wordIds.add(null);
                }
            }
            if (!fresh.isEmpty()) {
                List<UUID> inserted = words.insertBatch(userId, language, fresh);
                for (int index = 0; index < inserted.size(); index++) {
                    wordIds.set(freshSlots.get(index), inserted.get(index));
                }
                created += inserted.size();
            }
            for (UUID wordId : wordIds) {
                words.addBookSource(wordId, bookId, label);
                if (lists.insertItem(listId, wordId, ++position, now) > 0) {
                    listAdded++;
                } else {
                    listDuplicated++;
                }
            }
        }
        lists.touch(listId, now);
        // 首次加入才产生一行；重复加入保留首次 joined_at，不新增记录（AC-102.2）
        books.insertJoin(bookId, userId, now);
        int total = entries.size();
        log.debug("加入词书 book={} user={} created={} duplicated={} total={} list={} "
                        + "listAdded={} listDuplicated={}",
                bookId, userId, created, total - created, total, listId, listAdded, listDuplicated);
        return new JoinResult(created, total - created, total, listId);
    }

    /**
     * 建副本词单，同名加后缀（AC-102.5）：`书名` → `书名 (2)` → `书名 (3)`…
     *
     * <p>两种并发冲突都要兜住：名字被别人占了（换下一个后缀）与同一本书的副本被并发建出来
     * （`uk_vocab_lists_user_book` 冲突 → 直接用已存在的那份）。
     */
    private UUID createCopyList(UUID userId, BookRow book, Instant now) {
        int position = lists.nextPosition(userId);
        for (int attempt = 1; attempt <= MAX_SUFFIX_ATTEMPTS; attempt++) {
            String name = copyName(book.name(), attempt);
            if (lists.nameTaken(userId, name, null)) {
                continue;
            }
            UUID id = UUID.randomUUID();
            try {
                lists.createCopy(id, userId, name, null, position, book.id(), now);
                return id;
            } catch (DuplicateKeyException conflict) {
                Optional<UUID> concurrent = lists.findCopyListId(userId, book.id());
                if (concurrent.isPresent()) {
                    return concurrent.get();
                }
                // 否则是名字冲突：换下一个后缀继续
            }
        }
        throw ApiException.conflict("cannot create a copy list for this book");
    }

    /** 第 1 次用原名；第 N 次用 `name (N)`，并按 varchar(80) 截断基名。 */
    static String copyName(String base, int attempt) {
        String name = base == null || base.isBlank() ? "word book" : base.strip();
        if (attempt <= 1) {
            return truncate(name, MAX_LIST_NAME);
        }
        String suffix = " (" + attempt + ")";
        return truncate(name, MAX_LIST_NAME - suffix.length()) + suffix;
    }

    /** 按 code point 截断（`vocab_lists.name` 与 `vocab_books.name` 都是 varchar(80)）。 */
    static String truncate(String text, int maxCodePoints) {
        if (text == null) {
            return null;
        }
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, end);
    }
}
