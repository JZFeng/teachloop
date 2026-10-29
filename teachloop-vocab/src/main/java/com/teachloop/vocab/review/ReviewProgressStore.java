package com.teachloop.vocab.review;

import com.teachloop.vocab.srs.LearningState;
import com.teachloop.vocab.srs.SrsState;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 复习进度与复习日志的 SQL 唯一入口（T405 / US-53）。
 *
 * <p>收口规则（2026-10-29）：SQL 只写在 Store，Endpoint 不持有 {@code JdbcTemplate}。
 * SM-2 怎么算留在 {@link ReviewSubmitEndpoint}（策略对象），这里只负责读写行。
 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewProgressStore {

    private final JdbcTemplate jdbc;

    public ReviewProgressStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 进度行 + 词条的两个「不可复习」标记（软删 / 移除）。 */
    public record ProgressRow(LearningState state, double intervalDays, double ease,
                              int successes, int lapses, boolean removed, boolean deleted) {
    }

    public Optional<ProgressRow> load(UUID userId, UUID wordId) {
        return jdbc.query("""
                select p.state, p.interval_days, p.ease, p.successes, p.lapses, w.removed, w.deleted
                  from vocab_words w
                  join vocab_progress p on p.word_id = w.id
                 where w.id = ? and w.user_id = ?
                """, (rs, rowNumber) -> new ProgressRow(
                LearningState.valueOf(rs.getString(1)), rs.getBigDecimal(2).doubleValue(),
                rs.getBigDecimal(3).doubleValue(), rs.getInt(4), rs.getInt(5),
                rs.getBoolean(6), rs.getBoolean(7)),
                wordId, userId).stream().findFirst();
    }

    /**
     * 幂等键占位（AC-53.6）。
     *
     * @return true = 本次是第一次提交（占位成功）；false = 重复提交，调用方应直接返回当前进度
     */
    public boolean claimKey(UUID userId, String key, UUID wordId, Instant now) {
        int inserted = jdbc.update("""
                insert into vocab_review_requests (user_id, idempotency_key, word_id, created_at)
                values (?, ?, ?, ?) on conflict do nothing
                """, userId, key, wordId, Timestamp.from(now));
        return inserted > 0;
    }

    /**
     * @param dueAt       本次算出的下次到期时间（来自 {@code SrsDecision}，不在 {@code SrsState} 上）
     * @param ratingValue 落库值：1 = 不认识 / 2 = 认识（V042 的约束）
     */
    public void updateProgress(UUID wordId, SrsState next, Instant dueAt, int ratingValue, Instant now) {
        jdbc.update("""
                update vocab_progress
                   set state = ?, due_at = ?, interval_days = ?, ease = ?, successes = ?,
                       lapses = ?, last_rating = ?, reviewed_count = reviewed_count + 1, updated_at = ?
                 where word_id = ?
                """,
                next.state().name(), Timestamp.from(dueAt),
                BigDecimal.valueOf(next.intervalDays()), BigDecimal.valueOf(next.ease()),
                next.successes(), next.lapses(), ratingValue, Timestamp.from(now), wordId);
    }

    public void insertLog(UUID userId, UUID wordId, int ratingValue, Instant now,
                          Integer elapsedMs, UUID sessionId) {
        jdbc.update("""
                insert into vocab_review_logs
                    (user_id, word_id, rating, reviewed_at, elapsed_ms, session_id)
                values (?, ?, ?, ?, ?, ?)
                """, userId, wordId, ratingValue, Timestamp.from(now), elapsedMs, sessionId);
    }

    /** 当前到期时间；行不存在或 {@code due_at} 为 null 时返回 null。 */
    public Instant dueAt(UUID wordId) {
        List<Timestamp> due = jdbc.queryForList(
                "select due_at from vocab_progress where word_id = ?", Timestamp.class, wordId);
        return due.isEmpty() || due.get(0) == null ? null : due.get(0).toInstant();
    }
}
