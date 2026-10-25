package com.teachloop.vocab.review;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 自由复习会话持久化（T404 / US-61）：会话快照、游标推进与结束小结。
 *
 * <p>会话范围以 JSON 快照存进 `vocab_review_sessions.scope`（键 `key` = 范围指纹，
 * `wordIds` = 本次顺序），因此「继续复习剩余词」能复用同一份范围与游标。
 *
 * <p>只做数据访问；范围指纹计算、wordIds 解析与评分提交留在 {@link ReviewSessionEndpoint}。
 */
@Repository
public class ReviewSessionRepository {

    private static final RowMapper<SessionRow> SESSION_ROW_MAPPER = (rs, rowNumber) -> new SessionRow(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8));

    private final JdbcTemplate jdbc;

    public ReviewSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SessionRow(UUID id, UUID userId, String scopeJson, int total, int cursor,
                             int knownCount, int pausedCount, String status) {
    }

    /** 当前卡片的原始行；`content` 以 JSON 文本返回，解析留给端点。 */
    public record CurrentWordRow(UUID id, String form, String contentJson, String contentStatus,
                                 boolean favorite) {
    }

    /** 新建会话：cursor 从 0 或上次的值起（继续复习）。 */
    public void insertSession(UUID id, UUID userId, String scopeJson, int total, int cursor,
                              Instant now) {
        jdbc.update("""
                insert into vocab_review_sessions
                    (id, user_id, scope, total, cursor, known_count, paused_count, status,
                     started_at, updated_at)
                values (?, ?, cast(? as jsonb), ?, ?, 0, 0, 'ACTIVE', ?, ?)
                """, id, userId, scopeJson, total, cursor, Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<SessionRow> findOwned(UUID id, UUID userId) {
        return jdbc.query("""
                select id, user_id, cast(scope as text), total, cursor, known_count, paused_count, status
                  from vocab_review_sessions where id = ? and user_id = ?
                """, SESSION_ROW_MAPPER, id, userId).stream().findFirst();
    }

    /** 同一范围指纹下最近一次会话，用于「再次进入默认续位」。 */
    public Optional<SessionRow> latestByScope(UUID userId, String scopeKey) {
        return jdbc.query("""
                select id, user_id, cast(scope as text), total, cursor, known_count, paused_count, status
                  from vocab_review_sessions
                 where user_id = ? and scope->>'key' = ?
                 order by started_at desc limit 1
                """, SESSION_ROW_MAPPER, userId, scopeKey).stream().findFirst();
    }

    public Optional<CurrentWordRow> currentWord(UUID wordId, UUID userId) {
        return jdbc.query("""
                select id, form, cast(content as text), content_status, favorite from vocab_words
                 where id = ? and user_id = ? and deleted = false
                """, (rs, rowNumber) -> new CurrentWordRow(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBoolean(5)),
                wordId, userId).stream().findFirst();
    }

    public void advance(UUID id, int cursor, int knownIncrement, int pausedIncrement, Instant now) {
        jdbc.update("""
                update vocab_review_sessions
                   set cursor = ?, known_count = known_count + ?, paused_count = paused_count + ?,
                       updated_at = ?
                 where id = ?
                """, cursor, knownIncrement, pausedIncrement, Timestamp.from(now), id);
    }

    public void end(UUID id, Instant now) {
        jdbc.update("update vocab_review_sessions set status = 'ENDED', updated_at = ? where id = ?",
                Timestamp.from(now), id);
    }

    /** 小结用：本次会话里某个评分（1 = 不认识，2 = 认识）的条数。 */
    public int countLogs(UUID sessionId, int rating) {
        return jdbc.queryForObject(
                "select count(*) from vocab_review_logs where session_id = ? and rating = ?",
                Integer.class, sessionId, rating);
    }

    public boolean isPaused(UUID wordId) {
        List<Boolean> removed = jdbc.query(
                "select removed from vocab_words where id = ?",
                (rs, rowNumber) -> rs.getObject(1) == null ? null : rs.getBoolean(1), wordId);
        return !removed.isEmpty() && Boolean.TRUE.equals(removed.get(0));
    }
}
