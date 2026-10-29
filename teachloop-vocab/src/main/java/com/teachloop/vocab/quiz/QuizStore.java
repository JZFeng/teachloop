package com.teachloop.vocab.quiz;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 小测的 SQL 唯一入口（US-87 / T548）。
 *
 * <p>收口规则（2026-10-29 用户定的架构收尾 A）：<b>SQL 只写在 Repository/Store 里，
 * Endpoint 不持有 {@code JdbcTemplate}</b>。{@code scripts/check-boundaries.py} 会拦住
 * 违反这条规则的 {@code *Endpoint.java}。
 *
 * <p>这里只做「取行 / 写行」，不解析内容、不判分、不拼视图——那三件事留在
 * {@link QuizEndpoint}，所以本类对外只暴露 row record。
 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class QuizStore {

    private final JdbcTemplate jdbc;

    public QuizStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 行类型

    public record QuizRow(UUID id, UUID userId, String kind, String status, int itemCount,
                          Integer correctCount, Instant createdAt, Instant submittedAt,
                          UUID sourceSessionId) {
    }

    /** 一条题；{@code options} 是 jsonb 的文本形态，由 Endpoint 解析（不在 Store 里做 JSON）。 */
    public record ItemRow(UUID id, UUID wordId, int position, String questionType, String stem,
                          String options, String answerKey, String given, Boolean correct) {
    }

    /** 出卷/判分只需要词形、语言与内容引用；内容解析在 Endpoint 侧。 */
    public record WordRow(UUID id, String form, String language, Long contentId) {
    }

    /** 会话快照的原始 JSON 与游标。 */
    public record SessionRow(String scope, int cursor) {
    }

    // ---------------------------------------------------------------- 读

    /** 不校验归属：他人卷子要按「不存在」处理，这个判断留在 Endpoint（AC-87.11）。 */
    public Optional<QuizRow> findQuiz(UUID id) {
        return jdbc.query("""
                select id, user_id, kind, status, item_count, correct_count, created_at, submitted_at,
                       source_session_id
                  from vocab_quizzes where id = ?
                """, (rs, n) -> new QuizRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getInt(5),
                (Integer) rs.getObject(6), rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                rs.getObject(9, UUID.class)), id)
                .stream().findFirst();
    }

    public List<ItemRow> items(UUID quizId) {
        return jdbc.query("""
                select id, word_id, position, question_type, stem, cast(options as text), answer_key,
                       given, correct
                  from vocab_quiz_items where quiz_id = ? order by position
                """, (rs, n) -> new ItemRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), (Boolean) rs.getObject(9)), quizId);
    }

    /** 错题重练的词（去重、无序，与 SQL 的 {@code distinct} 同义）。 */
    public List<UUID> wrongWordIds(UUID quizId) {
        return jdbc.query("""
                select distinct word_id from vocab_quiz_items
                 where quiz_id = ? and correct = false
                """, (rs, n) -> rs.getObject(1, UUID.class), quizId);
    }

    /** 判分时取词形/词族用：不加用户过滤（词族是全局内容，不是学生私有）。 */
    public Optional<WordRow> word(UUID wordId) {
        return jdbc.query("""
                select id, form, language, content_id from vocab_words where id = ?
                """, (rs, n) -> new WordRow(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getObject(4, Long.class)), wordId)
                .stream().findFirst();
    }

    /**
     * 出卷用：必须是本人的、未软删、未移除的词条。
     *
     * <p>过滤条件写在这里而不是 Endpoint：它决定「哪一行算可用」，属于数据口径。
     */
    public Optional<WordRow> ownedWord(UUID wordId, UUID userId) {
        return jdbc.query("""
                select id, form, language, content_id from vocab_words
                 where id = ? and user_id = ? and deleted = false and removed = false
                """, (rs, n) -> new WordRow(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getObject(4, Long.class)), wordId, userId)
                .stream().findFirst();
    }

    public Optional<SessionRow> session(UUID sessionId, UUID userId) {
        return jdbc.query("""
                select scope, cursor from vocab_review_sessions where id = ? and user_id = ?
                """, (rs, n) -> new SessionRow(rs.getString(1), rs.getInt(2)), sessionId, userId)
                .stream().findFirst();
    }

    /** 今天复习过的词（不含小测自己：小测不写 review_logs）。 */
    public List<UUID> todayReviewedWordIds(UUID userId, Instant dayStart) {
        return jdbc.query("""
                select distinct l.word_id
                  from vocab_review_logs l
                  join vocab_words w on w.id = l.word_id
                 where l.user_id = ? and l.reviewed_at >= ?
                   and w.deleted = false and w.removed = false
                """, (rs, n) -> rs.getObject(1, UUID.class), userId, Timestamp.from(dayStart));
    }

    // ---------------------------------------------------------------- 写

    public void insertQuiz(UUID id, UUID userId, String kind, UUID sourceSessionId,
                           UUID sourceQuizId, int itemCount, long seed, Instant now) {
        jdbc.update("""
                insert into vocab_quizzes
                    (id, user_id, kind, source_session_id, source_quiz_id, status, item_count,
                     correct_count, seed, created_at, last_seen_at)
                values (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, null, ?, ?, ?)
                """, id, userId, kind, sourceSessionId, sourceQuizId, itemCount, seed,
                Timestamp.from(now), Timestamp.from(now));
    }

    public void insertItem(UUID id, UUID quizId, UUID userId, UUID wordId, int position,
                           String questionType, String stem, String optionsJson, String answerKey) {
        jdbc.update("""
                insert into vocab_quiz_items
                    (id, quiz_id, user_id, word_id, position, question_type, stem, options, answer_key)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """, id, quizId, userId, wordId, position, questionType, stem, optionsJson, answerKey);
    }

    public void updateItemAnswer(UUID itemId, String given, boolean correct, Instant now) {
        jdbc.update("""
                update vocab_quiz_items set given = ?, correct = ?, answered_at = ? where id = ?
                """, given, correct, Timestamp.from(now), itemId);
    }

    public void markSubmitted(UUID quizId, int correctCount, Instant now) {
        jdbc.update("""
                update vocab_quizzes
                   set status = 'SUBMITTED', correct_count = ?, submitted_at = ?, last_seen_at = ?
                 where id = ?
                """, correctCount, Timestamp.from(now), Timestamp.from(now), quizId);
    }

    /** 每次返回视图都摸一次 {@code last_seen_at}（未完成的卷子据此判断是否还在做）。 */
    public void touch(UUID quizId, Instant now) {
        jdbc.update("update vocab_quizzes set last_seen_at = ? where id = ?",
                Timestamp.from(now), quizId);
    }
}
