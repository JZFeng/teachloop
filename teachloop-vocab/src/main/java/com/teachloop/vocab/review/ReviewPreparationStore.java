package com.teachloop.vocab.review;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 按需准备（T426 / T429 / T431）的 SQL 唯一入口。
 *
 * <p>收口规则（2026-10-29）：SQL 只写在 Store，Endpoint 不持有 {@code JdbcTemplate}。
 * 「READY 能不能信」「命中全局内容后怎么回写」这些判断留在
 * {@link ReviewPreparationEndpoint}，这里只给行、只写行。
 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewPreparationStore {

    private final JdbcTemplate jdbc;

    public ReviewPreparationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一个窗口词的状态；{@code contentId} 为 null 表示还没解析过全局内容。 */
    public record WordRow(UUID id, String status, String language, String normalizedForm,
                          Long contentId) {
    }

    /** 与今日队列同一口径：到期、未暂停、未软删，按到期升序，取前 {@code window} 个。 */
    public List<WordRow> dueWords(UUID userId, String language, Instant now, int window) {
        return jdbc.query("""
                select w.id, w.content_status, w.language, w.normalized_form, w.content_id
                  from vocab_words w
                  join vocab_progress p on p.word_id = w.id
                 where w.user_id = ? and w.language = ? and w.deleted = false and w.removed = false
                   and p.due_at <= ?
                 order by p.due_at asc, w.id asc
                 limit ?
                """, (rs, rowNumber) -> new WordRow(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getObject(5, Long.class)),
                userId, language, Timestamp.from(now), window);
    }

    /** 自由复习：按 id 批量取（顺序由调用方按会话快照重排，SQL 不负责顺序）。 */
    public List<WordRow> wordsForSession(UUID userId, List<UUID> ids) {
        return jdbc.query("""
                select id, content_status, language, normalized_form, content_id from vocab_words
                 where user_id = ? and deleted = false and id = any (?)
                """, (rs, rowNumber) -> new WordRow(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getObject(5, Long.class)),
                userId, ids.toArray(new UUID[0]));
    }

    public List<WordRow> word(UUID userId, UUID wordId) {
        return jdbc.query("""
                select id, content_status, language, normalized_form, content_id from vocab_words
                 where id = ? and user_id = ? and deleted = false
                """, (rs, rowNumber) -> new WordRow(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getObject(5, Long.class)),
                wordId, userId);
    }

    /**
     * 内容解析不到时把词打回待生成：否则读路径会永远把「空内容」当 ready，
     * 学生看到的就是一张永远空白的卡（2026-10-29 用户报 Bug）。
     */
    public void markPending(UUID wordId, Instant now) {
        jdbc.update("""
                update vocab_words
                   set content_id = null, content_status = 'PENDING', updated_at = ?
                 where id = ?
                """, Timestamp.from(now), wordId);
    }

    /** T431：命中全局内容后只写引用 + 状态；用户行不再保存内容副本（V063 已删列）。 */
    public void markReady(UUID wordId, long contentId, Instant now) {
        jdbc.update("""
                update vocab_words
                   set content_id = ?, content_status = 'READY', updated_at = ?
                 where id = ?
                """, contentId, Timestamp.from(now), wordId);
    }
}
