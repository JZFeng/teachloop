package com.teachloop.vocab.review;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.content.StoredContent;
import com.teachloop.vocab.generation.VocabularyGenerationService;
import com.teachloop.vocab.review.ReviewSessionRepository.SessionRow;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 按需准备（T426 / US-48 US-52 US-61）：学生开始复习或打开详情时，只为马上要用的前 N 个词
 * 入队内容生成，任务带高优先级排到队首。
 *
 * <p>不做全量预生成：词库可能有几千个词，人背不完，全部准备的 AI 成本与等待都没有意义。
 * 前端按窗口懒加载：首批 {@code prepare-first}（默认 20），之后 20/100/200/400/… 逐级扩大。
 * 这里只负责「把这段窗口里还没内容的词排到队首」，进度以 {@code ready/pending/failed} 回报。
 *
 * <p>幂等：同一词已有 QUEUED/RUNNING 任务时不会重复建（表上的唯一键 + on conflict）。
 */
@RestController
@RequestMapping("/api/vocab/review")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewPreparationEndpoint {

    private final JdbcTemplate jdbc;
    private final ReviewSessionRepository sessions;
    private final VocabularyGenerationService generation;
    private final ContentStore contentStore;
    private final Clock clock;
    private final int defaultWindow;
    private final int maxWindow;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReviewPreparationEndpoint(
            JdbcTemplate jdbc,
            ReviewSessionRepository sessions,
            VocabularyGenerationService generation,
            ContentStore contentStore,
            Clock clock,
            @Value("${teachloop.vocab.review.prepare-first:20}") int defaultWindow,
            @Value("${teachloop.vocab.review.prepare-window-max:1000}") int maxWindow) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.generation = generation;
        this.contentStore = contentStore;
        this.clock = clock;
        this.defaultWindow = Math.max(1, defaultWindow);
        this.maxWindow = Math.max(this.defaultWindow, maxWindow);
    }

    public record PrepareRequest(String scope, UUID sessionId, UUID wordId, String language,
                                 Integer upTo) {
    }

    public record PrepareResult(String scope, int requested, int ready, int pending, int failed) {
    }

    /** 一个窗口词的状态；content_id 为 null 表示还没解析过全局内容。 */
    private record WordStatus(UUID id, String status, String language, String normalizedForm,
                              Long contentId) {
    }

    @PostMapping("/prepare")
    public PrepareResult prepare(@RequestBody(required = false) PrepareRequest request,
                                 @RequestAttribute("currentUser") CurrentUser me) {
        String scope = request == null || request.scope() == null || request.scope().isBlank()
                ? "TODAY" : request.scope().strip().toUpperCase();
        int window = clampWindow(request == null ? null : request.upTo());
        List<WordStatus> rows = switch (scope) {
            case "TODAY" -> dueWords(me.id(), request == null ? null : request.language(), window);
            case "SESSION" -> sessionWords(me.id(), request == null ? null : request.sessionId(), window);
            case "WORD" -> singleWord(me.id(), request == null ? null : request.wordId());
            default -> throw ApiException.badRequest("scope must be TODAY, SESSION or WORD");
        };
        List<UUID> missing = new ArrayList<>();
        int ready = 0;
        int pending = 0;
        int failed = 0;
        for (WordStatus row : rows) {
            if ("READY".equals(row.status())) {
                ready++;
                // 存量行可能还没有 content_id（P1/P2 过渡期）：能解析就补上引用。
                if (row.contentId() == null) {
                    resolve(row).ifPresent(content -> markReady(row, content));
                }
            } else if ("FAILED".equals(row.status())) {
                failed++;
            } else {
                // T429：学生 A 生成过的词，学生 B 进入复习时先查全局内容，命中就不再入队。
                var hit = resolve(row);
                if (hit.isPresent()) {
                    markReady(row, hit.get());
                    ready++;
                } else {
                    pending++;
                    missing.add(row.id());
                }
            }
        }
        generation.prepareOnDemand(missing);
        return new PrepareResult(scope, rows.size(), ready, pending, failed);
    }

    private int clampWindow(Integer upTo) {
        if (upTo == null || upTo <= 0) {
            return defaultWindow;
        }
        return Math.min(upTo, maxWindow);
    }

    /** 与今日队列同一口径：到期、未暂停、未软删，按到期升序。 */
    private List<WordStatus> dueWords(UUID userId, String language, int window) {
        String lang = language == null || language.isBlank() ? "en" : language;
        return jdbc.query("""
                select w.id, w.content_status, w.language, w.normalized_form, w.content_id
                  from vocab_words w
                  join vocab_progress p on p.word_id = w.id
                 where w.user_id = ? and w.language = ? and w.deleted = false and w.removed = false
                   and p.due_at <= ?
                 order by p.due_at asc, w.id asc
                 limit ?
                """, (rs, rowNumber) -> new WordStatus(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, Long.class)),
                userId, lang, Timestamp.from(clock.instant()), window);
    }

    /** 自由复习：会话快照里从当前 cursor 起的 window 个词（顺序即卡片顺序）。 */
    private List<WordStatus> sessionWords(UUID userId, UUID sessionId, int window) {
        if (sessionId == null) {
            throw ApiException.badRequest("sessionId is required for SESSION scope");
        }
        SessionRow session = sessions.findOwned(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("review session not found"));
        List<UUID> ids = wordIdsOf(session.scopeJson());
        int from = Math.min(Math.max(0, session.cursor()), ids.size());
        int to = Math.min(ids.size(), from + window);
        List<UUID> slice = ids.subList(from, to);
        if (slice.isEmpty()) {
            return List.of();
        }
        Map<UUID, WordStatus> statusById = new java.util.HashMap<>();
        jdbc.query("""
                select id, content_status, language, normalized_form, content_id from vocab_words
                 where user_id = ? and deleted = false and id = any (?)
                """, rs -> {
                    UUID id = rs.getObject(1, UUID.class);
                    statusById.put(id, new WordStatus(id, rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getObject(5, Long.class)));
                }, userId, slice.toArray(new UUID[0]));
        List<WordStatus> rows = new ArrayList<>(slice.size());
        for (UUID id : slice) {
            WordStatus row = statusById.get(id);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private List<WordStatus> singleWord(UUID userId, UUID wordId) {
        if (wordId == null) {
            throw ApiException.badRequest("wordId is required for WORD scope");
        }
        return jdbc.query("""
                select id, content_status, language, normalized_form, content_id from vocab_words
                 where id = ? and user_id = ? and deleted = false
                """, (rs, rowNumber) -> new WordStatus(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, Long.class)),
                wordId, userId);
    }

    private Optional<StoredContent> resolve(WordStatus row) {
        return contentStore.get(row.language(), row.normalizedForm());
    }

    /** T431：命中全局内容后只写引用 + 状态；用户行不再保存内容副本（V063 已删列）。 */
    private void markReady(WordStatus row, StoredContent content) {
        jdbc.update("""
                update vocab_words
                   set content_id = ?, content_status = 'READY', updated_at = ?
                 where id = ?
                """, content.id(), Timestamp.from(Instant.now(clock)), row.id());
    }

    private List<UUID> wordIdsOf(String scopeJson) {
        List<UUID> ids = new ArrayList<>();
        JsonNode root = mapper.readTree(scopeJson);
        JsonNode array = root.path("wordIds");
        if (array.isArray()) {
            for (JsonNode item : array) {
                if (item.isString()) {
                    ids.add(UUID.fromString(item.asString()));
                }
            }
        }
        return ids;
    }
}
