package com.teachloop.vocab.books;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 系统词书的持久化（T570–T575 / FR-231、232、251、252）：书、词条、加入记录。
 *
 * <p>三张表都属 vocab（`scripts/check-boundaries.py` 的 TABLE_OWNER），所以这里直接写 SQL，
 * 不跨模块 JOIN：`vocab_book_joins.user_id` 只是 UUID，不查 identity 的表。
 *
 * <p>分层：分页、去重与「哪些词已在学生词库」的判断在端点/服务；这里只做数据访问。
 * 词条唯一性由 `uk_vocab_book_entries (book_id, normalized_form)` 兜底，批量写入用
 * `on conflict do nothing`，因此同一段文本重复导入不会产生第二份词条（AC-100.3）。
 */
@Repository
public class BookRepository {

    /** 与 VocabWordRepository.IN_CHUNK 同口径：`in (...)` 一次最多 500 个占位符。 */
    private static final int IN_CHUNK = 500;

    private static final String BOOK_COLUMNS = """
            select id, slug, name, description, language, level, position, published,
                   pay_mode, price_cents, currency, created_at, updated_at
              from vocab_books
            """;

    private static final RowMapper<BookRow> BOOK_MAPPER = (rs, rowNumber) -> book(rs);

    private final JdbcTemplate jdbc;

    public BookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 书的完整行；`published` 状态机与三个收费字段都在这里（AC-105.1）。 */
    public record BookRow(UUID id, String slug, String name, String description, String language,
                          String level, int position, boolean published, String payMode,
                          int priceCents, String currency, Instant createdAt, Instant updatedAt) {
    }

    /**
     * 词条行。`id` 是 bigserial，词条删除接口按它定位（AC-100.5 只删词条，不删书）。
     */
    public record EntryRow(long id, String form, String normalizedForm, int position, String note) {
    }

    public record NewEntry(String form, String normalizedForm, int position, String note) {
    }

    public record EntryPage(List<EntryRow> items, long total) {
    }

    public List<BookRow> listAll() {
        return jdbc.query(BOOK_COLUMNS + " order by position asc, created_at asc, id asc", BOOK_MAPPER);
    }

    public List<BookRow> listPublished() {
        return jdbc.query(BOOK_COLUMNS + " where published order by position asc, created_at asc, id asc",
                BOOK_MAPPER);
    }

    /**
     * 推荐候选（T573 / AC-104.3）：v1 规则是「已发布 → position 升序 → 加入人数降序」。
     *
     * <p>排序写在 SQL 里、由 {@link BookRecommendationImpl} 调用，控制器不出现任何排序规则
     * （FR-250）。加入人数取 `vocab_book_joins` 计数，不新增计数表、不扫词库（FR-252）。
     */
    public List<BookRow> listForRecommendation(int limit) {
        return jdbc.query(BOOK_COLUMNS + """
                 where published
                 order by position asc,
                          (select count(*) from vocab_book_joins j where j.book_id = vocab_books.id) desc,
                          created_at asc, id asc
                 limit ?
                """, BOOK_MAPPER, limit);
    }

    public Optional<BookRow> find(UUID id) {
        return jdbc.query(BOOK_COLUMNS + " where id = ?", BOOK_MAPPER, id).stream().findFirst();
    }

    /** 学生端只认已发布的书；草稿与下架一律当作不存在（AC-101.1 / FR-236）。 */
    public Optional<BookRow> findPublished(UUID id) {
        return jdbc.query(BOOK_COLUMNS + " where id = ? and published", BOOK_MAPPER, id)
                .stream().findFirst();
    }

    public List<BookRow> findAllByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<BookRow> rows = new ArrayList<>();
        List<UUID> all = new ArrayList<>(ids);
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<UUID> chunk = all.subList(start, Math.min(start + IN_CHUNK, all.size()));
            rows.addAll(jdbc.query(BOOK_COLUMNS + " where id in ("
                    + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")",
                    BOOK_MAPPER, chunk.toArray()));
        }
        return rows;
    }

    public boolean slugTaken(String slug, UUID excludeId) {
        Integer count = excludeId == null
                ? jdbc.queryForObject("select count(*) from vocab_books where lower(slug) = lower(?)",
                        Integer.class, slug)
                : jdbc.queryForObject("select count(*) from vocab_books where lower(slug) = lower(?) and id <> ?",
                        Integer.class, slug, excludeId);
        return count != null && count > 0;
    }

    public void insert(BookRow book) {
        jdbc.update("""
                insert into vocab_books
                    (id, slug, name, description, language, level, position, published,
                     pay_mode, price_cents, currency, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, book.id(), book.slug(), book.name(), book.description(), book.language(),
                book.level(), book.position(), book.published(), book.payMode(), book.priceCents(),
                book.currency(), Timestamp.from(book.createdAt()), Timestamp.from(book.updatedAt()));
    }

    /** 元信息更新（端点先与当前行合并，只写完整行，避免「只改一个字段」的 SQL 漂移）。 */
    public int updateMeta(BookRow book) {
        return jdbc.update("""
                update vocab_books
                   set name = ?, description = ?, language = ?, level = ?, position = ?,
                       published = ?, pay_mode = ?, price_cents = ?, currency = ?, updated_at = ?
                 where id = ?
                """, book.name(), book.description(), book.language(), book.level(), book.position(),
                book.published(), book.payMode(), book.priceCents(), book.currency(),
                Timestamp.from(book.updatedAt()), book.id());
    }

    public int setPublished(UUID id, boolean published, Instant now) {
        return jdbc.update("update vocab_books set published = ?, updated_at = ? where id = ?",
                published, Timestamp.from(now), id);
    }

    public Map<UUID, Integer> wordCounts(Collection<UUID> bookIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (bookIds == null || bookIds.isEmpty()) {
            return counts;
        }
        List<UUID> all = new ArrayList<>(bookIds);
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<UUID> chunk = all.subList(start, Math.min(start + IN_CHUNK, all.size()));
            jdbc.query("select book_id, count(*) from vocab_book_entries where book_id in ("
                            + String.join(",", java.util.Collections.nCopies(chunk.size(), "?"))
                            + ") group by book_id",
                    rs -> {
                        counts.put(rs.getObject(1, UUID.class), rs.getInt(2));
                    }, chunk.toArray());
        }
        return counts;
    }

    public int wordCount(UUID bookId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from vocab_book_entries where book_id = ?", Integer.class, bookId);
        return count == null ? 0 : count;
    }

    public EntryPage entries(UUID bookId, int offset, int limit) {
        Long total = jdbc.queryForObject(
                "select count(*) from vocab_book_entries where book_id = ?", Long.class, bookId);
        List<EntryRow> items = jdbc.query("""
                select id, form, normalized_form, position, note
                  from vocab_book_entries
                 where book_id = ?
                 order by position asc, id asc
                 limit ? offset ?
                """, (rs, rowNumber) -> entry(rs), bookId, limit, offset);
        return new EntryPage(items, total == null ? 0 : total);
    }

    /** 加入事务与覆盖率都要按 position 顺序拿全部词条（一本书 ≤2000 词，一次取完比分页省事）。 */
    public List<EntryRow> allEntries(UUID bookId) {
        return jdbc.query("""
                select id, form, normalized_form, position, note
                  from vocab_book_entries
                 where book_id = ?
                 order by position asc, id asc
                """, (rs, rowNumber) -> entry(rs), bookId);
    }

    /** 覆盖率用：每本书的全部规范化词形（与书的语言配对由调用方负责）。 */
    public Map<UUID, List<String>> normalizedFormsByBook(Collection<UUID> bookIds) {
        Map<UUID, List<String>> forms = new HashMap<>();
        if (bookIds == null || bookIds.isEmpty()) {
            return forms;
        }
        List<UUID> all = new ArrayList<>(bookIds);
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<UUID> chunk = all.subList(start, Math.min(start + IN_CHUNK, all.size()));
            jdbc.query("select book_id, normalized_form from vocab_book_entries where book_id in ("
                            + String.join(",", java.util.Collections.nCopies(chunk.size(), "?"))
                            + ") order by book_id, position asc, id asc",
                    rs -> {
                        forms.computeIfAbsent(rs.getObject(1, UUID.class), key -> new ArrayList<>())
                                .add(rs.getString(2));
                    }, chunk.toArray());
        }
        return forms;
    }

    public int maxEntryPosition(UUID bookId) {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(position), 0) from vocab_book_entries where book_id = ?",
                Integer.class, bookId);
        return max == null ? 0 : max;
    }

    /**
     * 批量写词条；返回真正写入的行数（`on conflict do nothing` 时重复行不计数）。
     * 同书内 `normalized_form` 唯一（AC-100.3），非法行由调用方在入库前挡掉、不写库。
     */
    public int insertEntries(UUID bookId, List<NewEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(entries.size());
        for (NewEntry entry : entries) {
            batch.add(new Object[] {bookId, entry.form(), entry.normalizedForm(), entry.position(),
                    entry.note()});
        }
        int[] affected = jdbc.batchUpdate("""
                insert into vocab_book_entries (book_id, form, normalized_form, position, note)
                values (?, ?, ?, ?, ?)
                on conflict (book_id, normalized_form) do nothing
                """, batch);
        int inserted = 0;
        for (int count : affected) {
            if (count > 0) {
                inserted++;
            }
        }
        return inserted;
    }

    public int deleteEntry(UUID bookId, long entryId) {
        return jdbc.update("delete from vocab_book_entries where book_id = ? and id = ?", bookId, entryId);
    }

    /** 预览/提交时判断「这批词形里哪些书里已经有」（不区分大小写，靠 normalized_form）。 */
    public Set<String> existingNormalized(UUID bookId, Collection<String> normalizedForms) {
        Set<String> found = new LinkedHashSet<>();
        if (normalizedForms == null || normalizedForms.isEmpty()) {
            return found;
        }
        List<String> all = new ArrayList<>(normalizedForms);
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<String> chunk = all.subList(start, Math.min(start + IN_CHUNK, all.size()));
            List<Object> arguments = new ArrayList<>(chunk.size() + 1);
            arguments.add(bookId);
            arguments.addAll(chunk);
            jdbc.query("select normalized_form from vocab_book_entries where book_id = ?"
                            + " and normalized_form in ("
                            + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")",
                    rs -> {
                        found.add(rs.getString(1));
                    }, arguments.toArray());
        }
        return found;
    }

    /** 是否已加入（首次加入保留 joined_at，重复加入不新增行：AC-102.2）。 */
    public boolean joinExists(UUID bookId, UUID userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from vocab_book_joins where book_id = ? and user_id = ?",
                Integer.class, bookId, userId);
        return count != null && count > 0;
    }

    /** @return 1 = 新增一条加入记录；0 = 之前已加入（保留首次 joined_at） */
    public int insertJoin(UUID bookId, UUID userId, Instant now) {
        return jdbc.update("""
                insert into vocab_book_joins (book_id, user_id, joined_at)
                values (?, ?, ?)
                on conflict (book_id, user_id) do nothing
                """, bookId, userId, Timestamp.from(now));
    }

    public long joinCount(UUID bookId) {
        Long count = jdbc.queryForObject(
                "select count(*) from vocab_book_joins where book_id = ?", Long.class, bookId);
        return count == null ? 0 : count;
    }

    public Map<UUID, Long> joinCounts(Collection<UUID> bookIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (bookIds == null || bookIds.isEmpty()) {
            return counts;
        }
        List<UUID> all = new ArrayList<>(bookIds);
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<UUID> chunk = all.subList(start, Math.min(start + IN_CHUNK, all.size()));
            jdbc.query("select book_id, count(*) from vocab_book_joins where book_id in ("
                            + String.join(",", java.util.Collections.nCopies(chunk.size(), "?"))
                            + ") group by book_id",
                    rs -> {
                        counts.put(rs.getObject(1, UUID.class), rs.getLong(2));
                    }, chunk.toArray());
        }
        return counts;
    }

    public List<UUID> joinedBookIds(UUID userId, Collection<UUID> bookIds) {
        List<UUID> joined = new ArrayList<>();
        if (bookIds == null || bookIds.isEmpty()) {
            return joined;
        }
        List<UUID> all = new ArrayList<>(bookIds);
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<UUID> chunk = all.subList(start, Math.min(start + IN_CHUNK, all.size()));
            List<Object> arguments = new ArrayList<>(chunk.size() + 1);
            arguments.add(userId);
            arguments.addAll(chunk);
            jdbc.query("select book_id from vocab_book_joins where user_id = ? and book_id in ("
                            + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")",
                    rs -> {
                        joined.add(rs.getObject(1, UUID.class));
                    }, arguments.toArray());
        }
        return joined;
    }

    /**
     * 「已加入 N / M」里的 N（AC-101.2）：书里的词条有几个已经在我的词库。
     *
     * <p>口径是 `(user_id, language, normalized_form)`——与 `vocab_words` 的唯一键一致，
     * 软删的词不算（它不参与复习，学生看不到）。
     */
    public int entriesInVocab(UUID bookId, UUID userId, String language) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from vocab_book_entries e
                  join vocab_words w on w.normalized_form = e.normalized_form
                 where e.book_id = ? and w.user_id = ? and w.language = ? and w.deleted = false
                """, Integer.class, bookId, userId, language);
        return count == null ? 0 : count;
    }

    private static BookRow book(ResultSet rs) throws SQLException {
        return new BookRow(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getBoolean(8), rs.getString(9),
                rs.getInt(10), rs.getString(11), rs.getTimestamp(12).toInstant(),
                rs.getTimestamp(13).toInstant());
    }

    private static EntryRow entry(ResultSet rs) throws SQLException {
        return new EntryRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5));
    }
}
