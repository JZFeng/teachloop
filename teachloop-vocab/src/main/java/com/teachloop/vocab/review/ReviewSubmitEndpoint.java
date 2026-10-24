package com.teachloop.vocab.review;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.srs.LearningState;
import com.teachloop.vocab.srs.Rating;
import com.teachloop.vocab.srs.Sm2Strategy;
import com.teachloop.vocab.srs.SrsDecision;
import com.teachloop.vocab.srs.SrsState;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评分提交（T405 / US-53）：两档评分 → 内部 GOOD/AGAIN → SM-2 更新进度 + 写一条复习日志。
 *
 * <p>幂等：请求头 {@code Idempotency-Key}（同一学生内唯一）先占位；重复提交返回当前进度且
 * **不再写日志、不再推进调度**（AC-53.6）。日志 rating 落 1=不认识 / 2=认识（V042 约束）。
 */
@RestController
@RequestMapping("/api/vocab")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewSubmitEndpoint {

    private final JdbcTemplate jdbc;
    private final Sm2Strategy strategy;
    private final Clock clock;

    public ReviewSubmitEndpoint(JdbcTemplate jdbc, Sm2Strategy strategy, Clock clock) {
        this.jdbc = jdbc;
        this.strategy = strategy;
        this.clock = clock;
    }

    public record SubmitRequest(UUID wordId, String rating, Integer elapsedMs, UUID sessionId) {
    }

    public record SubmitResult(UUID wordId, String state, Instant dueAt, BigDecimal intervalDays,
                               BigDecimal ease, int successes, boolean duplicate) {
    }

    private record ProgressRow(LearningState state, double intervalDays, double ease,
                               int successes, int lapses, boolean removed, boolean deleted) {
    }

    @PostMapping("/review")
    @Transactional
    public SubmitResult submit(@RequestBody(required = false) SubmitRequest request,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                               @RequestAttribute("currentUser") CurrentUser me) {
        if (request == null || request.wordId() == null) {
            throw ApiException.badRequest("wordId is required");
        }
        boolean known = "KNOWN".equalsIgnoreCase(request.rating());
        if (!known && !"DONT_KNOW".equalsIgnoreCase(request.rating())) {
            throw ApiException.badRequest("rating must be KNOWN or DONT_KNOW");
        }
        ProgressRow progress = loadProgress(me.id(), request.wordId());
        if (progress == null || progress.deleted()) {
            throw ApiException.notFound("word not found");
        }

        String key = idempotencyKey == null ? null : idempotencyKey.strip();
        if (key != null && !key.isEmpty()) {
            int inserted = jdbc.update("""
                    insert into vocab_review_requests (user_id, idempotency_key, word_id, created_at)
                    values (?, ?, ?, ?) on conflict do nothing
                    """, me.id(), key, request.wordId(), Timestamp.from(clock.instant()));
            if (inserted == 0) {
                return result(me.id(), request.wordId(), true);
            }
        }

        Instant now = clock.instant();
        Rating rating = known ? Rating.GOOD : Rating.AGAIN;
        SrsDecision decision = strategy.schedule(
                new SrsState(progress.state(), progress.intervalDays(), progress.ease(),
                        progress.successes(), progress.lapses()),
                rating, now);
        SrsState next = decision.state();

        jdbc.update("""
                update vocab_progress
                   set state = ?, due_at = ?, interval_days = ?, ease = ?, successes = ?,
                       lapses = ?, last_rating = ?, reviewed_count = reviewed_count + 1, updated_at = ?
                 where word_id = ?
                """,
                next.state().name(), Timestamp.from(decision.dueAt()),
                BigDecimal.valueOf(next.intervalDays()), BigDecimal.valueOf(next.ease()),
                next.successes(), next.lapses(), known ? 2 : 1, Timestamp.from(now),
                request.wordId());
        jdbc.update("""
                insert into vocab_review_logs
                    (user_id, word_id, rating, reviewed_at, elapsed_ms, session_id)
                values (?, ?, ?, ?, ?, ?)
                """, me.id(), request.wordId(), known ? 2 : 1, Timestamp.from(now),
                request.elapsedMs(), request.sessionId());

        return new SubmitResult(request.wordId(), next.state().name(), decision.dueAt(),
                BigDecimal.valueOf(next.intervalDays()), BigDecimal.valueOf(next.ease()),
                next.successes(), false);
    }

    private SubmitResult result(UUID userId, UUID wordId, boolean duplicate) {
        ProgressRow row = loadProgress(userId, wordId);
        if (row == null) {
            throw ApiException.notFound("word not found");
        }
        List<java.sql.Timestamp> due = jdbc.queryForList(
                "select due_at from vocab_progress where word_id = ?", java.sql.Timestamp.class, wordId);
        return new SubmitResult(wordId, row.state().name(),
                due.isEmpty() ? null : due.get(0).toInstant(),
                BigDecimal.valueOf(row.intervalDays()), BigDecimal.valueOf(row.ease()),
                row.successes(), duplicate);
    }

    private ProgressRow loadProgress(UUID userId, UUID wordId) {
        List<ProgressRow> rows = jdbc.query("""
                select p.state, p.interval_days, p.ease, p.successes, p.lapses, w.removed, w.deleted
                  from vocab_words w
                  join vocab_progress p on p.word_id = w.id
                 where w.id = ? and w.user_id = ?
                """, (rs, rowNumber) -> new ProgressRow(
                LearningState.valueOf(rs.getString(1)), rs.getBigDecimal(2).doubleValue(),
                rs.getBigDecimal(3).doubleValue(), rs.getInt(4), rs.getInt(5),
                rs.getBoolean(6), rs.getBoolean(7)),
                wordId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
