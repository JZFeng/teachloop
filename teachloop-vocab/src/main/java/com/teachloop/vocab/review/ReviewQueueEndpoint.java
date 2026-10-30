package com.teachloop.vocab.review;

import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.content.ContentView;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
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
 * <p>响应同时给 T403 要的三个数：今日已复习（review_logs）、剩余待复习、本次新卡。
 * 「今日」的日界自 011（T557 / FR-228）起按学生时区算（{@code X-Timezone} &gt; users.timezone
 * &gt; 产品默认，见 {@link VocabZones}），不再硬编码 Asia/Shanghai。
 *
 * <p>SQL 在 {@link ReviewQueueStore}：Endpoint 不持有 {@code JdbcTemplate}（2026-10-29 收口）。
 */
@RestController
@RequestMapping("/api/vocab/review")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewQueueEndpoint {

    /** AC-87.9：小测错题提示只回看 30 天。 */
    private static final Duration QUIZ_MARK_WINDOW = Duration.ofDays(30);

    private final ReviewQueueStore store;
    private final ContentStore contentStore;
    private final Clock clock;
    private final ReviewPrefsStore prefs;
    private final VocabZones zones;
    private final int pageSize;

    public ReviewQueueEndpoint(ReviewQueueStore store, ContentStore contentStore, Clock clock,
                               ReviewPrefsStore prefs, VocabZones zones,
                               @Value("${teachloop.vocab.review.queue-page-size:100}") int pageSize) {
        this.store = store;
        this.contentStore = contentStore;
        this.clock = clock;
        this.prefs = prefs;
        this.zones = zones;
        this.pageSize = Math.max(1, pageSize);
    }

    /** language 供前端按词条语言选例句音色（en/zh/es），不再硬编码英文音。 */
    public record QueueItem(UUID id, String form, String language, JsonNode content,
                            String contentStatus, boolean favorite, String state, Instant dueAt,
                            BigDecimal intervalDays, BigDecimal ease, boolean newCard,
                            /* AC-87.9：该词最近一次小测答错的时间（30 天内），没有则为 null */
                            Instant lastQuizWrongAt) {
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
                           @RequestAttribute("currentUser") CurrentUser me,
                           /* 011 / FR-226：浏览器时区；缺失/非法都回落到资料与产品默认 */
                           @RequestHeader(value = "X-Timezone", required = false) String timezoneHeader) {
        String lang = language == null || language.isBlank() ? "en" : language;
        Timestamp afterTs = null;
        UUID afterId = null;
        if (after != null && !after.isBlank()) {
            String[] parts = after.split("\\|", 2);
            try {
                afterTs = Timestamp.from(Instant.parse(parts[0]));
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

        List<ReviewQueueStore.DueRow> due = store.due(me.id(), lang, clock.instant(), afterTs, afterId);

        int newDueTotal = 0;
        List<ReviewQueueStore.DueRow> selected = new ArrayList<>();
        int newSelected = 0;
        for (ReviewQueueStore.DueRow row : due) {
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
            List<ReviewQueueStore.DueRow> reviews = selected.stream().filter(row -> !row.newCard()).toList();
            List<ReviewQueueStore.DueRow> news = selected.stream().filter(ReviewQueueStore.DueRow::newCard).toList();
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
        long todayReviewed = countReviewedToday(me.id(), zones.resolve(timezoneHeader, me.id()));
        Map<UUID, Instant> quizMarks = store.wrongQuizMarks(me.id(),
                clock.instant().minus(QUIZ_MARK_WINDOW));
        List<QueueItem> items = selected.stream()
                .map(row -> toItem(row, quizMarks.get(row.id())))
                .toList();
        return new QueueView(items, due.size(), todayReviewed, remaining, newSelected,
                newDueTotal > newCap);
    }

    private QueueItem toItem(ReviewQueueStore.DueRow row, Instant lastQuizWrongAt) {
        JsonNode content = ContentView.resolve(contentStore, row.contentId(), row.language(), row.form());
        // 状态跟随内容：库里写的 READY 可能已经解析不到内容（内容包换版 / 提示词升级），
        // 这时按 PENDING 报给前端，页面显示「数据准备中」而不是一张空卡（2026-10-29 用户报 Bug）。
        return new QueueItem(row.id(), row.form(), row.language(), content,
                ContentView.statusFor(row.contentStatus(), content), row.favorite(), row.state(),
                row.dueAt(), row.intervalDays(), row.ease(), row.newCard(), lastQuizWrongAt);
    }

    /** 「今天已复习」的日界：学生时区当天 0 点（AC-99.x）；端口实现共用同一份口径。 */
    private long countReviewedToday(UUID userId, ZoneId zone) {
        return store.countReviewedToday(userId, zones.dayStart(zone));
    }
}
