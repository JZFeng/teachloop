package com.teachloop.vocab.review;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.lists.VocabularyListRepository;
import com.teachloop.vocab.content.ContentView;
import com.teachloop.vocab.review.ReviewSessionRepository.SessionRow;
import com.teachloop.vocab.word.VocabWordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * <p>分层：范围指纹、wordIds 解析与评分提交留在这里；SQL 在 {@link ReviewSessionRepository}。
 */
@RestController
@RequestMapping("/api/vocab/review-sessions")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewSessionEndpoint {

    private static final int MAX_WORDS = 1000;

    private final ReviewSessionRepository sessions;
    private final ReviewSubmitEndpoint submit;
    private final VocabWordRepository words;
    private final VocabularyListRepository lists;
    private final ContentStore contentStore;
    private final ReviewPrefsStore prefs;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReviewSessionEndpoint(ReviewSessionRepository sessions, ReviewSubmitEndpoint submit,
                                 VocabWordRepository words, VocabularyListRepository lists,
                                 ContentStore contentStore, ReviewPrefsStore prefs, Clock clock) {
        this.sessions = sessions;
        this.submit = submit;
        this.words = words;
        this.lists = lists;
        this.contentStore = contentStore;
        this.prefs = prefs;
        this.clock = clock;
    }

    /**
     * 开一轮复习：二选一。
     * <ul>
     *   <li>{@code wordIds}：沿用旧行为（前端自己取范围，≤1000）；</li>
     *   <li>{@code listId}：服务端展开词单里的词（T576 / FR-248），词单属于本人否则 404。
     *       超过 1000 词时只取「还没进过任何会话」的下一批，批次之间不重叠；一轮背完再从头。</li>
     * </ul>
     */
    /**
     * `fromStart` 必须是包装类型：Jackson 3 默认 `FAIL_ON_NULL_FOR_PRIMITIVES`，
     * 只传 `{"listId": ...}` 时基本类型会因「缺失即 null」抛 500（2026-10-30 实测）。
     * null 按 false 处理，与旧客户端的语义一致。
     */
    public record CreateRequest(List<UUID> wordIds, Boolean fromStart, UUID listId) {
    }

    public record SessionView(UUID id, int total, int cursor, int lastCursor, String scopeKey) {
    }

    /** language 供前端按词条语言选例句音色（en/zh/es）。 */
    public record CurrentWord(UUID id, String form, String language, JsonNode content,
                              String contentStatus, boolean favorite, boolean paused, int cursor,
                              int total, Instant lastQuizWrongAt) {
    }

    public record AdvanceRequest(String action, Integer elapsedMs) {
    }

    public record AdvanceResult(int cursor, int total, boolean done) {
    }

    public record Summary(int total, int seen, int known, int unknown, int paused) {
    }

    @PostMapping
    @Transactional
    public SessionView create(@RequestBody(required = false) CreateRequest request,
                              @RequestAttribute("currentUser") CurrentUser me) {
        if (request != null && request.listId() != null) {
            return createForList(request, me);
        }
        if (request == null || request.wordIds() == null || request.wordIds().isEmpty()) {
            throw ApiException.badRequest("wordIds or listId is required");
        }
        if (request.wordIds().size() > MAX_WORDS) {
            throw ApiException.badRequest("at most " + MAX_WORDS + " words per session");
        }
        List<UUID> eligible = new ArrayList<>();
        for (UUID wordId : request.wordIds()) {
            if (words.belongsTo(me.id(), wordId) && !sessions.isPaused(wordId)) {
                eligible.add(wordId);
            }
        }
        if (eligible.isEmpty()) {
            throw ApiException.badRequest("no reviewable words");
        }
        String scopeKey = scopeKey(eligible);
        SessionRow previous = sessions.latestByScope(me.id(), scopeKey).orElse(null);
        int cursor = !restart(request) && previous != null ? previous.cursor() : 0;
        UUID id = UUID.randomUUID();
        String scope = mapper.writeValueAsString(Map.of("key", scopeKey, "wordIds", eligible));
        sessions.insertSession(id, me.id(), scope, eligible.size(), cursor, clock.instant());
        return new SessionView(id, eligible.size(), cursor, cursor, scopeKey);
    }

    /**
     * 按词单开会话（T576 / AC-102.4）：范围 = 词单里未暂停、未软删的词（按加入顺序），
     * 不含词单外的词；沿用单会话 1000 词上限——超出的部分按「已进过会话的词」跳过，
     * 所以 2000 词的书会分成两批且不重叠。
     */
    private SessionView createForList(CreateRequest request, CurrentUser me) {
        UUID listId = request.listId();
        lists.findOwned(listId, me.id())
                .orElseThrow(() -> ApiException.notFound("word list not found"));
        List<UUID> eligible = lists.reviewableWordIds(listId);
        if (eligible.isEmpty()) {
            throw ApiException.badRequest("no reviewable words");
        }
        List<UUID> batch = eligible;
        if (eligible.size() > MAX_WORDS) {
            Set<UUID> alreadyUsed = usedWordIds(me.id(), listId);
            List<UUID> remaining = new ArrayList<>();
            for (UUID wordId : eligible) {
                if (!alreadyUsed.contains(wordId)) {
                    remaining.add(wordId);
                }
            }
            if (remaining.isEmpty()) {
                // 一轮已经背完：重新从头开始，不因为「都背过」卡住学生
                remaining = eligible;
            }
            batch = remaining.subList(0, Math.min(MAX_WORDS, remaining.size()));
        }
        String scopeKey = scopeKey(batch);
        SessionRow previous = sessions.latestByScope(me.id(), scopeKey).orElse(null);
        int cursor = !restart(request) && previous != null ? previous.cursor() : 0;
        UUID id = UUID.randomUUID();
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("key", scopeKey);
        scope.put("wordIds", batch);
        scope.put("listId", listId.toString());
        sessions.insertSession(id, me.id(), mapper.writeValueAsString(scope), batch.size(), cursor,
                clock.instant());
        return new SessionView(id, batch.size(), cursor, cursor, scopeKey);
    }

    /** `fromStart` 缺省（null）按「续位」处理：与旧客户端只传 wordIds 时的行为一致。 */
    private static boolean restart(CreateRequest request) {
        return Boolean.TRUE.equals(request.fromStart());
    }

    /** 这个词单已经进过会话的词（跨会话取并集），用于把 1000+ 词的书切成不重叠的批次。 */
    private Set<UUID> usedWordIds(UUID userId, UUID listId) {
        Set<UUID> used = new HashSet<>();
        for (String scope : sessions.scopesForList(userId, listId)) {
            used.addAll(wordIds(scope));
        }
        return used;
    }

    @GetMapping("/{id}/current")
    public CurrentWord current(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        SessionRow session = requireSession(id, me.id());
        List<UUID> ids = wordIds(session.scopeJson());
        if (session.cursor() >= ids.size()) {
            return null;
        }
        UUID wordId = ids.get(session.cursor());
        return sessions.currentWord(wordId, me.id())
                .map(row -> {
                    // T594：自由复习的卡面与今日复习同一份口径——blocks 的顺序与隐藏都听用户偏好，
                    // 前端不再按 key 硬编码取值/排序。
                    ReviewPrefs pref = prefs.load(me.id());
                    JsonNode content = ContentView.resolve(contentStore, row.contentId(),
                            row.language(), row.form(), pref.blocks(), pref.hidden());
                    // 状态跟随内容：库里的 READY 过期（内容包换版 / 提示词升级）时降级成 PENDING，
                    // 前端才会显示「数据准备中」而不是一张空卡（2026-10-29 用户报 Bug）。
                    return new CurrentWord(row.id(), row.form(), row.language(), content,
                            ContentView.statusFor(row.contentStatus(), content), row.favorite(),
                            row.paused(), session.cursor(), session.total(),
                            wrongQuizMark(me.id(), row.id()));
                })
                .orElse(null);
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
            case "SKIP" -> {
                // T436：明细视图的「下一个」——只推进游标，不记日志、不动排期
            }
            case "TOGGLE_PAUSE" -> {
                // T437：卡片头部的暂停/恢复开关——原地切换，不推进游标；
                // 恢复只撤销暂停、保留进度（不是 AC-59.3 的「回到新词」）。
                boolean paused = sessions.isPaused(wordId);
                boolean changed = paused ? words.unpause(me.id(), wordId) : words.pause(me.id(), wordId);
                if (!changed) {
                    throw ApiException.notFound("word not found");
                }
                sessions.advance(id, session.cursor(), 0, paused ? -1 : 1, clock.instant());
                return new AdvanceResult(session.cursor(), session.total(), false);
            }
            case "KNOWN", "DONT_KNOW" -> {
                submit.submit(new ReviewSubmitEndpoint.SubmitRequest(wordId, action,
                        request.elapsedMs(), id), null, me);
                if ("KNOWN".equals(action)) {
                    knownIncrement = 1;
                }
            }
            default -> throw ApiException.badRequest("action must be KNOWN, DONT_KNOW, PAUSE, SKIP or TOGGLE_PAUSE");
        }
        int cursor = session.cursor() + 1;
        sessions.advance(id, cursor, knownIncrement, pausedIncrement, clock.instant());
        return new AdvanceResult(cursor, session.total(), cursor >= ids.size());
    }

    /** 「继续复习剩余词」：用同一份范围快照再开一个会话，从上次 cursor 继续。 */
    @PostMapping("/{id}/continue")
    public SessionView continueSession(@PathVariable UUID id,
                                       @RequestAttribute("currentUser") CurrentUser me) {
        SessionRow session = requireSession(id, me.id());
        UUID newId = UUID.randomUUID();
        Instant now = clock.instant();
        sessions.insertSession(newId, me.id(), session.scopeJson(), session.total(),
                session.cursor(), now);
        return new SessionView(newId, session.total(), session.cursor(), session.cursor(), null);
    }

    @PostMapping("/{id}/end")
    public Summary end(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        SessionRow session = requireSession(id, me.id());
        sessions.end(id, clock.instant());
        return new Summary(session.total(), session.cursor(),
                sessions.countLogs(id, 2), sessions.countLogs(id, 1), session.pausedCount());
    }

    /** AC-87.9：自由复习的卡面也要能看到「上次小测没答对」，与今日复习同一口径（30 天内）。 */
    private Instant wrongQuizMark(UUID userId, UUID wordId) {
        return sessions.wrongQuizMark(userId, wordId, clock.instant().minus(java.time.Duration.ofDays(30)));
    }

    private SessionRow requireSession(UUID id, UUID userId) {
        return sessions.findOwned(id, userId)
                .orElseThrow(() -> ApiException.notFound("session not found"));
    }

    private List<UUID> wordIds(String scopeJson) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode node : mapper.readTree(scopeJson).path("wordIds")) {
            ids.add(UUID.fromString(node.asString()));
        }
        return ids;
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
