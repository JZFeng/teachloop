package com.teachloop.vocab.review;

import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.content.ContentView;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 今日复习队列（T402 / US-52）。
 *
 * <p>队列 = 到期时间 ≤ now、未暂停、未软删的词，按到期升序；NEW 卡受每日上限
 * `teachloop.vocab.review.daily-new-cards` 约束（超出部分次日再进队列），到期卡不限量；
 * 单次最多 `review.queue-page-size`（默认 100）。同一个词只出现一次。
 *
 * <p>响应同时给 T403 要的三个数：今日已复习（review_logs）、剩余待复习、本次新卡；
 * 「今日」按部署时区 Asia/Shanghai 计算（spec A-3）。
 */
@RestController
@RequestMapping("/api/vocab/review")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewQueueEndpoint {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final ContentStore contentStore;
    private final Clock clock;
    private final ReviewPrefsStore prefs;
    private final int pageSize;

    public ReviewQueueEndpoint(JdbcTemplate jdbc, ContentStore contentStore, Clock clock,
                               ReviewPrefsStore prefs,
                               @Value("${teachloop.vocab.review.queue-page-size:100}") int pageSize) {
        this.jdbc = jdbc;
        this.contentStore = contentStore;
        this.clock = clock;
        this.prefs = prefs;
        this.pageSize = Math.max(1, pageSize);
    }

    /** language 供前端按词条语言选例句音色（en/zh/es），不再硬编码英文音。 */
    public record QueueItem(UUID id, String form, String language, JsonNode content,
                            String contentStatus, boolean favorite, String state, Instant dueAt,
                            BigDecimal intervalDays, BigDecimal ease, boolean newCard,
                            /* AC-87.9：该词最近一次小测答错的时间（30 天内），没有则为 null */
                            Instant lastQuizWrongAt) {
    }

    /**
     * 到期行的原始数据：内容先不解析。
     *
     * <p>队列可能一次扫出几千条到期卡再按 cap 截断；内容查找只对真正返回的卡片做，
     * 避免按 content_id 为整条队列做无用查找（plan §4.1.6 命中路径目标）。
     */
    private record QueueRow(UUID id, String form, String language, Long contentId,
                            String contentStatus, boolean favorite, String state, Instant dueAt,
                            BigDecimal intervalDays, BigDecimal ease, boolean newCard) {

        QueueItem toItem(JsonNode content, Instant lastQuizWrongAt) {
            // 状态跟随内容：库里写的 READY 可能已经解析不到内容（内容包换版 / 提示词升级），
            // 这时按 PENDING 报给前端，页面显示「数据准备中」而不是一张空卡（2026-10-29 用户报 Bug）。
            return new QueueItem(id, form, language, content,
                    ContentView.statusFor(contentStatus, content), favorite, state,
                    dueAt, intervalDays, ease, newCard, lastQuizWrongAt);
        }
    }

    public record QueueView(List<QueueItem> items, int total, long todayReviewed, int remaining,
                            int newToday, boolean newLimitReached) {
    }

    @GetMapping("/queue")
    public QueueView queue(@RequestParam(required = false) String language,
                           @RequestParam(required = false) Integer limit,
                           /* AC-89.1：预取的游标，格式 "<ISO instant>|<wordId>"；与排序键 (due_at, id) 同构。
                              不用 epoch 毫秒：timestamptz 是微秒精度，截到毫秒会让同一行在下一页再出现一次。 */
                           @RequestParam(required = false) String after,
                           /* 已经出过的新卡数：预取时配额要接着算，不能每页重新给满 */
                           @RequestParam(required = false, defaultValue = "0") int newServed,
                           @RequestAttribute("currentUser") CurrentUser me) {
        String lang = language == null || language.isBlank() ? "en" : language;
        Timestamp afterTs = null;
        UUID afterId = null;
        if (after != null && !after.isBlank()) {
            String[] parts = after.split("\\|", 2);
            try {
                afterTs = Timestamp.from(java.time.Instant.parse(parts[0]));
                afterId = parts.length > 1 ? UUID.fromString(parts[1]) : null;
            } catch (RuntimeException badCursor) {
                throw com.teachloop.shared.error.ApiException.badRequest("after must be <isoInstant>|<wordId>");
            }
            if (afterId == null) {
                throw com.teachloop.shared.error.ApiException.badRequest("after must be <isoInstant>|<wordId>");
            }
        }
        int cap = Math.min(limit == null || limit <= 0 ? pageSize : limit, pageSize);
        // AC-88.3：拿用户偏好，再被全局硬上限压一次（属性值退化为「上限 + 缺省值」）
        ReviewPrefs pref = prefs.load(me.id());
        int newCap = Math.max(0, Math.min(pref.dailyNewCards(), prefs.maxCards()) - Math.max(0, newServed));

        List<QueueRow> due = jdbc.query("""
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
                """, (rs, rowNumber) -> new QueueRow(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getObject(4, Long.class),
                        rs.getString(5),
                        rs.getBoolean(6),
                        rs.getString(7),
                        rs.getTimestamp(8).toInstant(),
                        rs.getBigDecimal(9),
                        rs.getBigDecimal(10),
                        "NEW".equals(rs.getString(7))),
                me.id(), lang, Timestamp.from(clock.instant()),
                afterTs, afterTs, afterTs, afterId);

        int newDueTotal = 0;
        List<QueueRow> selected = new ArrayList<>();
        int newSelected = 0;
        for (QueueRow row : due) {
            if (row.newCard()) {
                newDueTotal++;
                if (newSelected >= newCap || selected.size() >= cap) {
                    continue;
                }
                newSelected++;
                selected.add(row);
            } else if (selected.size() < cap) {
                selected.add(row);
            }
        }
        // AC-88.4：策略只改「谁先出」，不改配额与计数
        if (pref.reviewFirst() || pref.newFirst()) {
            List<QueueRow> reviews = selected.stream().filter(row -> !row.newCard()).toList();
            List<QueueRow> news = selected.stream().filter(QueueRow::newCard).toList();
            selected.clear();
            if (pref.newFirst()) {
                selected.addAll(news);
                selected.addAll(reviews);
            } else {
                selected.addAll(reviews);
                selected.addAll(news);
            }
        }
        int remaining = due.size() - Math.max(0, newDueTotal - Math.min(newDueTotal, newCap));
        long todayReviewed = countReviewedToday(me.id());
        Map<UUID, Instant> quizMarks = wrongQuizMarks(me.id());
        List<QueueItem> items = selected.stream()
                .map(row -> row.toItem(ContentView.resolve(contentStore, row.contentId(),
                        row.language(), row.form()), quizMarks.get(row.id())))
                .toList();
        return new QueueView(items, due.size(), todayReviewed, remaining, newSelected,
                newDueTotal > newCap);
    }

    /**
     * AC-87.9：每个词最近一次「小测答错」的交卷时间。
     *
     * <p>一次查完整批（不按词逐个查），并且只取 30 天内的——一行提示挂一辈子就没人看了。
     */
    private Map<UUID, Instant> wrongQuizMarks(UUID userId) {
        Map<UUID, Instant> marks = new java.util.HashMap<>();
        Instant since = clock.instant().minus(java.time.Duration.ofDays(30));
        jdbc.query("""
                select i.word_id, max(q.submitted_at) as last_at
                  from vocab_quiz_items i
                  join vocab_quizzes q on q.id = i.quiz_id
                 where i.user_id = ? and i.correct = false and q.submitted_at >= ?
                 group by i.word_id
                """, rs -> {
            marks.put(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant());
        }, userId, Timestamp.from(since));
        return marks;
    }

    private long countReviewedToday(UUID userId) {
        Instant dayStart = LocalDate.ofInstant(clock.instant(), ZONE).atStartOfDay(ZONE).toInstant();
        Long count = jdbc.queryForObject("""
                select count(*) from vocab_review_logs
                 where user_id = ? and reviewed_at >= ? and session_id is null
                """, Long.class, userId, Timestamp.from(dayStart));
        return count == null ? 0 : count;
    }
}
