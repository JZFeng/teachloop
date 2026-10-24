package com.teachloop.vocab.review;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.word.VocabWordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 词库内自由复习会话（T404 / US-61）。
 *
 * <p>范围 = 当前筛选命中的词（由前端取齐后以 wordIds 传入，≤1000；已暂停/软删的词直接剔除）。
 * 不受到期时间与每日新卡限制；「认识」照常推进调度（复用 T405 的评分提交，日志带 session_id），
 * 「暂停复习」只停该词排期并跳下一张；随时结束并给出小结；再次进入默认从上次 cursor 续位。
 *
 * <p>本期不写入「今日复习」完成数：队列的 todayReviewed 只数 `session_id is null` 的日志。
 */
@RestController
@RequestMapping("/api/vocab/review-sessions")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewSessionEndpoint {

    private static final int MAX_WORDS = 1000;

    private final JdbcTemplate jdbc;
    private final ReviewSubmitEndpoint submit;
    private final VocabWordRepository words;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReviewSessionEndpoint(JdbcTemplate jdbc, ReviewSubmitEndpoint submit,
                                 VocabWordRepository words, Clock clock) {
        this.jdbc = jdbc;
        this.submit = submit;
        this.words = words;
        this.clock = clock;
    }

    public record CreateRequest(List<UUID> wordIds, boolean fromStart) {
    }

    public record SessionView(UUID id, int total, int cursor, int lastCursor, String scopeKey) {
    }

    public record CurrentWord(UUID id, String form, JsonNode content, int cursor, int total) {
    }

    public record AdvanceRequest(String action, Integer elapsedMs) {
    }

    public record AdvanceResult(int cursor, int total, boolean done) {
    }

    public record Summary(int total, int seen, int known, int unknown, int paused) {
    }

    private record SessionRow(UUID id, UUID userId, String scopeJson, int total, int cursor,
                              int knownCount, int pausedCount, String status) {
    }

    @PostMapping
    @Transactional
    public SessionView create(@RequestBody(required = false) CreateRequest request,
                              @RequestAttribute("currentUser") CurrentUser me) {
        if (request == null || request.wordIds() == null || request.wordIds().isEmpty()) {
            throw ApiException.badRequest("wordIds is required");
        }
        if (request.wordIds().size() > MAX_WORDS) {
            throw ApiException.badRequest("at most " + MAX_WORDS + " words per session");
        }
        List<UUID> eligible = new ArrayList<>();
        for (UUID wordId : request.wordIds()) {
            if (words.belongsTo(me.id(), wordId) && !isPaused(wordId)) {
                eligible.add(wordId);
            }
        }
        if (eligible.isEmpty()) {
            throw ApiException.badRequest("no reviewable words");
        }
        String scopeKey = scopeKey(eligible);
        SessionRow previous = latestByScope(me.id(), scopeKey);
        int cursor = !request.fromStart() && previous != null ? previous.cursor() : 0;
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String scope = mapper.writeValueAsString(Map.of("key", scopeKey, "wordIds", eligible));
        jdbc.update("""
                insert into vocab_review_sessions
                    (id, user_id, scope, total, cursor, known_count, paused_count, status,
                     started_at, updated_at)
                values (?, ?, cast(? as jsonb), ?, ?, 0, 0, 'ACTIVE', ?, ?)
                """, id, me.id(), scope, eligible.size(), cursor,
                Timestamp.from(now), Timestamp.from(now));
        return new SessionView(id, eligible.size(), cursor, cursor, scopeKey);
    }

    @GetMapping("/{id}/current")
    public CurrentWord current(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        SessionRow session = requireSession(id, me.id());
        List<UUID> ids = wordIds(session.scopeJson());
        if (session.cursor() >= ids.size()) {
            return null;
        }
        UUID wordId = ids.get(session.cursor());
        List<CurrentWord> rows = jdbc.query("""
                        select id, form, cast(content as text) from vocab_words
                         where id = ? and user_id = ? and deleted = false
                        """, (rs, rowNumber) -> new CurrentWord(
                        rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3) == null ? null : mapper.readTree(rs.getString(3)),
                        session.cursor(), session.total()),
                wordId, me.id());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @PostMapping("/{id}/advance")
    @Transactional
    public AdvanceResult advance(@PathVariable UUID id, @RequestBody(required = false) AdvanceRequest request,
                                 @RequestAttribute("currentUser") CurrentUser me) {
        SessionRow session = requireSession(id, me.id());
        List<UUID> ids = wordIds(session.scopeJson());
        if (session.cursor() >= ids.size()) {
            return new AdvanceResult(session.cursor(), session.total(), true);
        }
        UUID wordId = ids.get(session.cursor());
        String action = request == null || request.action() == null ? "KNOWN" : request.action();
        int pausedIncrement = 0;
        int knownIncrement = 0;
        switch (action) {
            case "PAUSE" -> {
                if (!words.pause(me.id(), wordId)) {
                    throw ApiException.notFound("word not found");
                }
                pausedIncrement = 1;
            }
            case "KNOWN", "DONT_KNOW" -> {
                submit.submit(new ReviewSubmitEndpoint.SubmitRequest(wordId, action,
                        request.elapsedMs(), id), null, me);
                if ("KNOWN".equals(action)) {
                    knownIncrement = 1;
                }
            }
            default -> throw ApiException.badRequest("action must be KNOWN, DONT_KNOW or PAUSE");
        }
        int cursor = session.cursor() + 1;
        jdbc.update("""
                update vocab_review_sessions
                   set cursor = ?, known_count = known_count + ?, paused_count = paused_count + ?,
                       updated_at = ?
                 where id = ?
                """, cursor, knownIncrement, pausedIncrement, Timestamp.from(clock.instant()), id);
        return new AdvanceResult(cursor, session.total(), cursor >= ids.size());
    }

    /** 「继续复习剩余词」：用同一份范围快照再开一个会话，从上次 cursor 继续。 */
    @PostMapping("/{id}/continue")
    public SessionView continueSession(@PathVariable UUID id,
                                       @RequestAttribute("currentUser") CurrentUser me) {
        SessionRow session = requireSession(id, me.id());
        List<UUID> ids = wordIds(session.scopeJson());
        UUID newId = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                insert into vocab_review_sessions
                    (id, user_id, scope, total, cursor, known_count, paused_count, status,
                     started_at, updated_at)
                values (?, ?, cast(? as jsonb), ?, ?, 0, 0, 'ACTIVE', ?, ?)
                """, newId, me.id(), session.scopeJson(), session.total(), session.cursor(),
                Timestamp.from(now), Timestamp.from(now));
        return new SessionView(newId, session.total(), session.cursor(), session.cursor(), null);
    }

    @PostMapping("/{id}/end")
    public Summary end(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        SessionRow session = requireSession(id, me.id());
        jdbc.update("update vocab_review_sessions set status = 'ENDED', updated_at = ? where id = ?",
                Timestamp.from(clock.instant()), id);
        int known = jdbc.queryForObject(
                "select count(*) from vocab_review_logs where session_id = ? and rating = 2",
                Integer.class, id);
        int unknown = jdbc.queryForObject(
                "select count(*) from vocab_review_logs where session_id = ? and rating = 1",
                Integer.class, id);
        return new Summary(session.total(), session.cursor(), known, unknown, session.pausedCount());
    }

    private SessionRow requireSession(UUID id, UUID userId) {
        List<SessionRow> rows = jdbc.query("""
                select id, user_id, cast(scope as text), total, cursor, known_count, paused_count, status
                  from vocab_review_sessions where id = ? and user_id = ?
                """, (rs, rowNumber) -> new SessionRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8)),
                id, userId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("session not found");
        }
        return rows.get(0);
    }

    private SessionRow latestByScope(UUID userId, String scopeKey) {
        List<SessionRow> rows = jdbc.query("""
                select id, user_id, cast(scope as text), total, cursor, known_count, paused_count, status
                  from vocab_review_sessions
                 where user_id = ? and scope->>'key' = ?
                 order by started_at desc limit 1
                """, (rs, rowNumber) -> new SessionRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8)),
                userId, scopeKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<UUID> wordIds(String scopeJson) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode node : mapper.readTree(scopeJson).path("wordIds")) {
            ids.add(UUID.fromString(node.asString()));
        }
        return ids;
    }

    private boolean isPaused(UUID wordId) {
        Boolean removed = jdbc.queryForObject(
                "select removed from vocab_words where id = ?", Boolean.class, wordId);
        return Boolean.TRUE.equals(removed);
    }

    private static String scopeKey(List<UUID> wordIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(wordIds.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
