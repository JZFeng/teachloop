package com.teachloop.vocab.review;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 今日复习队列的 SQL 唯一入口（T402 / US-52）。
 *
 * <p>收口规则（2026-10-29）：SQL 只写在 Store，Endpoint 不持有 {@code JdbcTemplate}。
 * 这里只负责「取到期行 / 取小测错题时间 / 数今日已复习」，配额、排序策略与内容解析
 * 都留在 {@link ReviewQueueEndpoint}——那些是业务判断，改起来不该动 SQL。
 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewQueueStore {

    private final JdbcTemplate jdbc;

    public ReviewQueueStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 到期行的原始数据：内容先不解析。
     *
     * <p>队列可能一次扫出几千条到期卡再按 cap 截断；内容查找只对真正返回的卡片做，
     * 避免按 content_id 为整条队列做无用查找（plan §4.1.6 命中路径目标）。
     */
    public record DueRow(UUID id, String form, String language, Long contentId,
                         String contentStatus, boolean favorite, String state, Instant dueAt,
                         BigDecimal intervalDays, BigDecimal ease) {

        /** NEW 卡 = 还没学过；由 state 派生，不额外占一列。 */
        public boolean newCard() {
            return "NEW".equals(state);
        }
    }

    /**
     * 到期行，按 {@code (due_at, id)} 升序。
     *
     * @param afterTs 预取游标（AC-89.1）：为 null 表示第一页；用 timestamptz 而不是 epoch 毫秒，
     *                因为 timestamptz 是微秒精度，截到毫秒会让同一行在下一页再出现一次。
     */
    public List<DueRow> due(UUID userId, String language, Instant now,
                            Timestamp afterTs, UUID afterId) {
        return jdbc.query("""
                select w.id, w.form, w.language, w.content_id, w.content_status, w.favorite,
                       p.state, p.due_at, p.interval_days, p.ease
                  from vocab_words w
                  join vocab_progress p on p.word_id = w.id
                 where w.user_id = ? and w.language = ? and w.deleted = false and w.removed = false
                   and p.due_at <= ?
                   and (cast(? as timestamptz) is null
                        or p.due_at > cast(? as timestamptz)
                        or (p.due_at = cast(? as timestamptz) and w.id > cast(? as uuid)))
                 order by p.due_at asc, w.id asc
                """, (rs, rowNumber) -> new DueRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getObject(4, Long.class),
                rs.getString(5),
                rs.getBoolean(6),
                rs.getString(7),
                rs.getTimestamp(8).toInstant(),
                rs.getBigDecimal(9),
                rs.getBigDecimal(10)),
                userId, language, Timestamp.from(now), afterTs, afterTs, afterTs, afterId);
    }

    /**
     * AC-87.9：每个词最近一次「小测答错」的交卷时间。
     *
     * <p>一次查完整批（不按词逐个查），并且只取 {@code since} 之后的——一行提示挂一辈子就没人看了。
     */
    public Map<UUID, Instant> wrongQuizMarks(UUID userId, Instant since) {
        Map<UUID, Instant> marks = new HashMap<>();
        jdbc.query("""
                select i.word_id, max(q.submitted_at) as last_at
                  from vocab_quiz_items i
                  join vocab_quizzes q on q.id = i.quiz_id
                 where i.user_id = ? and i.correct = false and q.submitted_at >= ?
                 group by i.word_id
                """, (rs, rowNumber) -> Map.entry(rs.getObject(1, UUID.class),
                rs.getTimestamp(2).toInstant()),
                userId, Timestamp.from(since))
                .forEach(entry -> marks.put(entry.getKey(), entry.getValue()));
        return marks;
    }

    /** 今日已复习数：只数不带 session 的评分（小测不写 review_logs，见 AC-87.7）。 */
    public long countReviewedToday(UUID userId, Instant dayStart) {
        Long count = jdbc.queryForObject("""
                select count(*) from vocab_review_logs
                 where user_id = ? and reviewed_at >= ? and session_id is null
                """, Long.class, userId, Timestamp.from(dayStart));
        return count == null ? 0 : count;
    }
}
