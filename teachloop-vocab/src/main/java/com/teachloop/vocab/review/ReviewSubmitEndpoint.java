package com.teachloop.vocab.review;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.srs.Rating;
import com.teachloop.vocab.srs.Sm2Strategy;
import com.teachloop.vocab.srs.SrsDecision;
import com.teachloop.vocab.srs.SrsState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * <p>SQL 在 {@link ReviewProgressStore}：Endpoint 不持有 {@code JdbcTemplate}（2026-10-29 收口）。
 */
@RestController
@RequestMapping("/api/vocab")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewSubmitEndpoint {

    /** 日志与 {@code last_rating} 的落库编码（V042 的约束）：1 = 不认识，2 = 认识。 */
    private static final int RATING_AGAIN = 1;
    private static final int RATING_GOOD = 2;

    private final ReviewProgressStore store;
    private final Sm2Strategy strategy;
    private final Clock clock;

    public ReviewSubmitEndpoint(ReviewProgressStore store, Sm2Strategy strategy, Clock clock) {
        this.store = store;
        this.strategy = strategy;
        this.clock = clock;
    }

    public record SubmitRequest(UUID wordId, String rating, Integer elapsedMs, UUID sessionId) {
    }

    public record SubmitResult(UUID wordId, String state, Instant dueAt, BigDecimal intervalDays,
                               BigDecimal ease, int successes, boolean duplicate) {
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
        ReviewProgressStore.ProgressRow progress = store.load(me.id(), request.wordId())
                .orElseThrow(() -> ApiException.notFound("word not found"));
        if (progress.deleted()) {
            throw ApiException.notFound("word not found");
        }

        String key = idempotencyKey == null ? null : idempotencyKey.strip();
        if (key != null && !key.isEmpty()) {
            if (!store.claimKey(me.id(), key, request.wordId(), clock.instant())) {
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

        store.updateProgress(request.wordId(), next, decision.dueAt(), known ? RATING_GOOD : RATING_AGAIN, now);
        store.insertLog(me.id(), request.wordId(), known ? RATING_GOOD : RATING_AGAIN, now,
                request.elapsedMs(), request.sessionId());

        return new SubmitResult(request.wordId(), next.state().name(), decision.dueAt(),
                BigDecimal.valueOf(next.intervalDays()), BigDecimal.valueOf(next.ease()),
                next.successes(), false);
    }

    private SubmitResult result(UUID userId, UUID wordId, boolean duplicate) {
        ReviewProgressStore.ProgressRow row = store.load(userId, wordId)
                .orElseThrow(() -> ApiException.notFound("word not found"));
        return new SubmitResult(wordId, row.state().name(), store.dueAt(wordId),
                BigDecimal.valueOf(row.intervalDays()), BigDecimal.valueOf(row.ease()),
                row.successes(), duplicate);
    }
}
