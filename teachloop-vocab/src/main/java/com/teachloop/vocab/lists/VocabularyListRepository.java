package com.teachloop.vocab.lists;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 学生词单持久化（T449 / US-69）：词单元数据、引用行与软删除。
 *
 * <p>只做数据访问，不做权限判定与字段校验——这些留在 {@link VocabularyListEndpoint}。
 * 归属由 `vocab_lists.user_id` 保证：所有读写都带 `user_id` 条件，因此「他人词单」在
 * SQL 层就是 0 行，端点按 404 处理（AC-69.9）。
 *
 * <p>词单**只引用** `word_id`（V064 刻意不给 `vocab_list_items.word_id` 建外键）：
 * 软删词（AC-55）的引用行保留，恢复后它仍在原来所属的词单里；因此查词单里的词是
 * {@code join vocab_words}，并把 `deleted` / `removed` 原样带回给调用方，由界面标注
 * 「已删除」/「已暂停」。
 *
 * <p>进度不在这里：`vocab_progress` 每个词只有一行，词单不复制排期（AC-69.1）。
 */
@Repository
public class VocabularyListRepository {

    /** 词单视图（列表与单条共用同一份投影，避免两处漂移）。 */
    private static final String LIST_VIEW_SELECT = """
            select l.id, l.name, l.description, l.position, l.updated_at,
                   (select count(*) from vocab_list_items i where i.list_id = l.id) as word_count
              from vocab_lists l
            """;

    private static final RowMapper<ListView> LIST_VIEW_MAPPER = (rs, rowNumber) -> new ListView(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4),
            rs.getInt(6), rs.getTimestamp(5).toInstant());

    private static final RowMapper<ListWordView> LIST_WORD_MAPPER = (rs, rowNumber) -> new ListWordView(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3) == null ? null : rs.getInt(3),
            rs.getString(4),
            rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
            rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8), rs.getInt(9),
            rs.getTimestamp(10).toInstant());

    private final JdbcTemplate jdbc;

    public VocabularyListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ListView(UUID id, String name, String description, int position, int wordCount,
                           Instant updatedAt) {
    }

    /** 词单里的一行：词条 + 进度摘要；`deleted` / `paused` 由界面标注（不参与复习）。 */
    public record ListWordView(UUID id, String form, Integer band, String state, Instant dueAt,
                               boolean favorite, boolean paused, boolean deleted, int position,
                               Instant addedAt) {
    }

    public record ListWordPage(List<ListWordView> items, long total, int page, int size) {
    }

    public List<ListView> listByStudent(UUID userId) {
        return jdbc.query(LIST_VIEW_SELECT + """
                 where l.user_id = ? and l.deleted = false
                 order by l.position asc, l.created_at asc
                """, LIST_VIEW_MAPPER, userId);
    }

    public Optional<ListView> findOwned(UUID id, UUID userId) {
        return jdbc.query(LIST_VIEW_SELECT + """
                 where l.id = ? and l.user_id = ? and l.deleted = false
                """, LIST_VIEW_MAPPER, id, userId).stream().findFirst();
    }

    /** 当前未删除词单数（上限预检用，AC-69.4）。 */
    public int countActive(UUID userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from vocab_lists where user_id = ? and deleted = false",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** 下一个排序位：V1 不提供拖动排序，顺序 = 加入/创建顺序（AC-69.1）。 */
    public int nextPosition(UUID userId) {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(position), 0) from vocab_lists where user_id = ?",
                Integer.class, userId);
        return (max == null ? 0 : max) + 1;
    }

    /** 重名预检（大小写不敏感，AC-69.1）；`excludeId` 用于改名时不与自己比。 */
    public boolean nameTaken(UUID userId, String name, UUID excludeId) {
        if (excludeId == null) {
            Integer count = jdbc.queryForObject("""
                    select count(*) from vocab_lists
                     where user_id = ? and deleted = false and lower(name) = lower(?)
                    """, Integer.class, userId, name);
            return count != null && count > 0;
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from vocab_lists
                 where user_id = ? and deleted = false and lower(name) = lower(?) and id <> ?
                """, Integer.class, userId, name, excludeId);
        return count != null && count > 0;
    }

    /** 新建词单；并发同名由唯一索引抛 {@code DuplicateKeyException}，端点转 409。 */
    public void create(UUID id, UUID userId, String name, String description, int position,
                       Instant now) {
        jdbc.update("""
                insert into vocab_lists (id, user_id, name, description, position, deleted,
                                         created_at, updated_at)
                values (?, ?, ?, ?, ?, false, ?, ?)
                """, id, userId, name, description, position, Timestamp.from(now),
                Timestamp.from(now));
    }

    public int updateMeta(UUID id, UUID userId, String name, String description, Instant now) {
        return jdbc.update("""
                update vocab_lists set name = ?, description = ?, updated_at = ?
                 where id = ? and user_id = ? and deleted = false
                """, name, description, Timestamp.from(now), id, userId);
    }

    /** 软删词单（AC-69.3）：只删容器，引用行按 `on delete cascade` 留着也无意义，
     * 但**词条与进度一行不动**——本 SQL 不触碰 vocab_words / vocab_progress。 */
    public int softDelete(UUID id, UUID userId, Instant now) {
        return jdbc.update("""
                update vocab_lists set deleted = true, updated_at = ?
                 where id = ? and user_id = ? and deleted = false
                """, Timestamp.from(now), id, userId);
    }

    public ListWordPage wordsPage(UUID listId, int page, int size) {
        Long total = jdbc.queryForObject(
                "select count(*) from vocab_list_items where list_id = ?", Long.class, listId);
        List<ListWordView> items = jdbc.query("""
                select w.id, w.form, w.band, p.state, p.due_at, w.favorite, w.removed, w.deleted,
                       i.position, i.added_at
                  from vocab_list_items i
                  join vocab_words w on w.id = i.word_id
                  left join vocab_progress p on p.word_id = w.id
                 where i.list_id = ?
                 order by i.position asc, i.id asc
                 limit ? offset ?
                """, LIST_WORD_MAPPER, listId, size, (long) (page - 1) * size);
        return new ListWordPage(items, total == null ? 0 : total, page, size);
    }

    /** 复习范围：这份词单里未暂停、未软删的词，按加入顺序（AC-69.7 / AC-61.1 同一排除口径）。 */
    public List<UUID> reviewableWordIds(UUID listId) {
        return jdbc.query("""
                select i.word_id
                  from vocab_list_items i
                  join vocab_words w on w.id = i.word_id
                 where i.list_id = ? and w.removed = false and w.deleted = false
                 order by i.position asc, i.id asc
                """, (rs, rowNumber) -> rs.getObject(1, UUID.class), listId);
    }

    public record ListSize(int total, int paused, int deleted) {
    }

    /** 词单三计数：全部引用、其中已暂停、其中已软删（详情页写「N 词 / M 个已暂停不在复习范围」）。 */
    public ListSize size(UUID listId) {
        return jdbc.queryForObject("""
                select count(*) as total,
                       count(*) filter (where w.removed) as paused,
                       count(*) filter (where w.deleted) as deleted
                  from vocab_list_items i
                  join vocab_words w on w.id = i.word_id
                 where i.list_id = ?
                """, (rs, rowNumber) -> new ListSize(
                rs.getInt(1), rs.getInt(2), rs.getInt(3)), listId);
    }

    public int maxItemPosition(UUID listId) {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(position), 0) from vocab_list_items where list_id = ?",
                Integer.class, listId);
        return max == null ? 0 : max;
    }

    /**
     * 追加一条引用；返回 0 表示该词已在这份词单里（唯一键冲突被忽略），调用方据此计 duplicated。
     * 同一份词单内同一个词只挂一次、重复加入不报错（AC-69.2）。
     */
    public int insertItem(UUID listId, UUID wordId, int position, Instant now) {
        return jdbc.update("""
                insert into vocab_list_items (list_id, word_id, position, added_at)
                values (?, ?, ?, ?)
                on conflict (list_id, word_id) do nothing
                """, listId, wordId, position, Timestamp.from(now));
    }

    /** 移出：只删引用，不动词条与进度（AC-69.6）。 */
    public int removeItem(UUID listId, UUID wordId) {
        return jdbc.update("delete from vocab_list_items where list_id = ? and word_id = ?",
                listId, wordId);
    }

    /** 词单内容变动后刷新 updated_at（列表按它排序）。 */
    public void touch(UUID listId, Instant now) {
        jdbc.update("update vocab_lists set updated_at = ? where id = ?",
                Timestamp.from(now), listId);
    }

    /** 按名字找本人未删除的词单 id（导入时指定词单名先复用，AC-69.5）。 */
    public Optional<UUID> findActiveIdByName(UUID userId, String name) {
        return jdbc.query("""
                select id from vocab_lists
                 where user_id = ? and deleted = false and lower(name) = lower(?)
                """, (rs, rowNumber) -> rs.getObject(1, UUID.class), userId, name).stream().findFirst();
    }

    /**
     * 某个导入批次写进 {@code vocab_word_sources} 的词 id。
     *
     * <p>导入提交只回 counts 与 batchId，不逐条回词 id；「导入时指定词单名」要靠批次号反查
     * 这一批到底写进了哪些词（AC-69.5），所以走来源表，不靠猜。
     */
    public List<UUID> wordIdsByBatch(UUID userId, UUID batchId) {
        return jdbc.query("""
                select w.id from vocab_word_sources s join vocab_words w on w.id = s.word_id
                 where s.batch_id = ? and w.user_id = ?
                 order by s.id asc
                """, (rs, rowNumber) -> rs.getObject(1, UUID.class), batchId, userId);
    }
}
