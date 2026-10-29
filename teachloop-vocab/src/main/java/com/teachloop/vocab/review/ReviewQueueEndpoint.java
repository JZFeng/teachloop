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
    private final int dailyNewCards;
    private final int pageSize;

    public ReviewQueueEndpoint(JdbcTemplate jdbc, ContentStore contentStore, Clock clock,
                               @Value("${teachloop.vocab.review.daily-new-cards:20}") int dailyNewCards,
                               @Value("${teachloop.vocab.review.queue-page-size:100}") int pageSize) {
        this.jdbc = jdbc;
        this.contentStore = contentStore;
        this.clock = clock;
        this.dailyNewCards = Math.max(0, dailyNewCards);
        this.pageSize = Math.max(1, pageSize);
    }

    /** language 供前端按词条语言选例句音色（en/zh/es），不再硬编码英文音。 */
    public record QueueItem(UUID id, String form, String language, JsonNode content,
                            String contentStatus, boolean favorite, String state, Instant dueAt,
                            BigDecimal intervalDays, BigDecimal ease, boolean newCard) {
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

        QueueItem toItem(JsonNode content) {
            // 状态跟随内容：库里写的 READY 可能已经解析不到内容（内容包换版 / 提示词升级），
            // 这时按 PENDING 报给前端，页面显示「数据准备中」而不是一张空卡（2026-10-29 用户报 Bug）。
            return new QueueItem(id, form, language, content,
                    ContentView.statusFor(contentStatus, content), favorite, state,
                    dueAt, intervalDays, ease, newCard);
        }
    }

    public record QueueView(List<QueueItem> items, int total, long todayReviewed, int remaining,
                            int newToday, boolean newLimitReached) {
    }

    @GetMapping("/queue")
    public QueueView queue(@RequestParam(required = false) String language,
                           @RequestParam(required = false) Integer limit,
                           @RequestAttribute("currentUser") CurrentUser me) {
        String lang = language == null || language.isBlank() ? "en" : language;
        int cap = Math.min(limit == null || limit <= 0 ? pageSize : limit, pageSize);
        int newCap = dailyNewCards;

        List<QueueRow> due = jdbc.query("""
                select w.id, w.form, w.language, w.content_id, w.content_status, w.favorite,
                       p.state, p.due_at, p.interval_days, p.ease
                  from vocab_words w
                  join vocab_progress p on p.word_id = w.id
                 where w.user_id = ? and w.language = ? and w.deleted = false and w.removed = false
                   and p.due_at <= ?
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
                me.id(), lang, Timestamp.from(clock.instant()));

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
        int remaining = due.size() - Math.max(0, newDueTotal - Math.min(newDueTotal, newCap));
        long todayReviewed = countReviewedToday(me.id());
        List<QueueItem> items = selected.stream()
                .map(row -> row.toItem(ContentView.resolve(contentStore, row.contentId(),
                        row.language(), row.form())))
                .toList();
        return new QueueView(items, due.size(), todayReviewed, remaining, newSelected,
                newDueTotal > newCap);
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
