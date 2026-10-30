package com.teachloop.app.student;

import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.shared.time.ZoneResolution;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 011 / T559 / AC-94.x：学生「今日」页的唯一聚合端点。
 *
 * <p>本类只做四件事：**权限 → 时区 → 取数（三段各自兜底）→ 排序拼装**。
 * 业务表的片段查询在属主模块里（vocab 的 {@link Ports.ReviewSlicePort}、sitting 的
 * {@link Ports.AgendaPort}、identity 的 {@link Ports.UserZonePort}），app 里没有任何 SQL——
 * 这条由 {@code check-boundaries} 的 app 扫描守着（FR-230）。
 *
 * <p>降级约定（AC-94.2）：任一段抛异常只把该段置空并把 {@code partial.<段>} 写成 {@code error}，
 * 整体仍返回 200。前端据此只让那一张卡显示错误与重试，其余卡片照常渲染。
 *
 * <p>响应在 spec §7.1 的形状上多了三个字段（都是只读展示用，不改变已有字段语义）：
 * <ul>
 *   <li>{@code partial}：三段各自的状态，plan §4.6 明确要求；</li>
 *   <li>{@code inClass}：前端要能区分「在班但没作业」与「根本没班级」（AC-90.3 vs AC-90.5），
 *       只有 {@code assignments/exams} 两个空数组无法区分；</li>
 *   <li>{@code zone} / {@code defaultZone}：AC-99.5 的「新的一天按 &lt;时区&gt; 0 点开始」需要
 *       服务端实际用的时区与产品默认时区才能判断该不该显示。</li>
 * </ul>
 * 作业不在这里截断：AC-94.1 要求返回全部待办，卡片「最多 3 条 + 页内展开」是展示规则，
 * 截在后端会让「查看全部」永远只有 3 条。
 */
@RestController
@RequestMapping("/api/student")
public class StudentTodayEndpoint {

    private static final Logger log = LoggerFactory.getLogger(StudentTodayEndpoint.class);

    /** AC-90.4 / FR-201：考试只取未来 7 天。 */
    private static final Duration EXAM_WINDOW = Duration.ofDays(7);

    private final ObjectProvider<Ports.ReviewSlicePort> reviewSlices;
    private final Ports.AgendaPort agenda;
    private final Ports.ClassMembership members;
    private final Ports.UserZonePort userZones;
    private final Clock clock;
    private final ZoneId productZone;
    private final String productZoneId;

    public StudentTodayEndpoint(ObjectProvider<Ports.ReviewSlicePort> reviewSlices,
                                Ports.AgendaPort agenda,
                                Ports.ClassMembership members,
                                Ports.UserZonePort userZones,
                                Clock clock,
                                @Value("${teachloop.timezone.default:Asia/Shanghai}") String productZoneId) {
        this.reviewSlices = reviewSlices;
        this.agenda = agenda;
        this.members = members;
        this.userZones = userZones;
        this.clock = clock;
        this.productZoneId = productZoneId;
        this.productZone = ZoneResolution.resolve(null, Optional.empty(), ZoneId.of(productZoneId));
    }

    // ──────────────────────────────────────────────────────────── 响应形状

    public record ReviewView(int due, int reviewedToday, int newToday, int newLimit) {
        static ReviewView empty() {
            return new ReviewView(0, 0, 0, 0);
        }
    }

    public record AssignmentView(UUID id, String title, String className, Instant dueAt, String status) {
    }

    public record ExamView(UUID id, String title, String className, Instant startsAt) {
    }

    public record PartialView(String review, String assignments, String exams) {
        static PartialView ok() {
            return new PartialView("ok", "ok", "ok");
        }
    }

    public record TodayView(ReviewView review, List<AssignmentView> assignments, List<ExamView> exams,
                            PartialView partial, boolean inClass, String zone, String defaultZone) {
    }

    // ──────────────────────────────────────────────────────────── 端点

    @GetMapping("/today")
    public TodayView today(@RequestAttribute("currentUser") CurrentUser me,
                           @RequestHeader(value = "X-Timezone", required = false) String timezoneHeader) {
        // AC-94.3：教师/管理员不报错，只是三段空值；前端按角色不渲染学生卡片。
        if (me == null || !me.is("STUDENT")) {
            return empty(productZone.getId());
        }

        ZoneId zone = resolveZone(timezoneHeader, me.id());
        Instant now = clock.instant();

        String reviewStatus = "ok";
        ReviewView review;
        try {
            Ports.ReviewSlicePort slice = reviewSlices.getIfAvailable();
            // vocab 模块关闭时没有实现：按「今天没有要复习的」返回，不报错（AC-91.4 的深链回落同理）。
            review = slice == null ? ReviewView.empty() : fromSlice(slice.today(me.id(), zone.getId()));
        } catch (RuntimeException ex) {
            // 复习段失败不能连累作业/考试（AC-94.2）。
            log.warn("today review slice failed user={}: {}", me.id(), ex.toString());
            review = ReviewView.empty();
            reviewStatus = "error";
        }

        // 在班与否只影响「哪些卡片出现」：这里一次 O(1) 查询，不对 offering 逐条请求（AC-90.7）。
        boolean inClass = false;
        try {
            inClass = !members.classIdsOf(me.id()).isEmpty();
        } catch (RuntimeException ex) {
            log.warn("today class membership failed user={}: {}", me.id(), ex.toString());
        }

        String assignmentStatus = "ok";
        List<AssignmentView> assignments;
        try {
            assignments = sortAssignments(agenda.pending(me.id()), now);
        } catch (RuntimeException ex) {
            log.warn("today assignments failed user={}: {}", me.id(), ex.toString());
            assignments = List.of();
            assignmentStatus = "error";
        }

        String examStatus = "ok";
        List<ExamView> exams;
        try {
            exams = agenda.upcoming(me.id(), now, now.plus(EXAM_WINDOW)).stream()
                    .sorted(Comparator.comparing(Ports.ExamRow::startsAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(row -> new ExamView(row.id(), row.title(), row.className(), row.startsAt()))
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("today exams failed user={}: {}", me.id(), ex.toString());
            exams = List.of();
            examStatus = "error";
        }

        return new TodayView(review, assignments, exams,
                new PartialView(reviewStatus, assignmentStatus, examStatus), inClass,
                zone.getId(), productZone.getId());
    }

    // ──────────────────────────────────────────────────────────── 内部

    /** AC-99.1：头 &gt; 资料 &gt; 产品默认；任一环失败都回落，不 500（AC-99.2）。 */
    private ZoneId resolveZone(String header, UUID userId) {
        Optional<String> stored = Optional.empty();
        try {
            stored = userZones.of(userId).map(Ports.UserZone::iana);
        } catch (RuntimeException ex) {
            log.warn("today user timezone lookup failed user={}: {}", userId, ex.toString());
        }
        if (header != null && ZoneResolution.parse(header) == null) {
            log.warn("today ignoring invalid X-Timezone header user={}", userId);
        }
        return ZoneResolution.resolve(header, stored, productZone);
    }

    private static ReviewView fromSlice(Ports.ReviewSlice slice) {
        if (slice == null) {
            return ReviewView.empty();
        }
        return new ReviewView(slice.due(), slice.reviewedToday(), slice.newToday(), slice.newLimit());
    }

    /**
     * AC-90.2 / FR-200：逾期在前（逾期更久的更前），其余按截止时间升序。
     * 不截断——「最多 3 条 + 页内展开」由前端做（AC-94.1 要求返回全部）。
     */
    static List<AssignmentView> sortAssignments(List<Ports.AssignmentRow> rows, Instant now) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .sorted(Comparator
                        .comparingInt((Ports.AssignmentRow row) -> isOverdue(row.dueAt(), now) ? 0 : 1)
                        .thenComparing(Ports.AssignmentRow::dueAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(row -> new AssignmentView(row.id(), row.title(), row.className(),
                        row.dueAt(), row.status()))
                .toList();
    }

    private static boolean isOverdue(Instant dueAt, Instant now) {
        return dueAt != null && dueAt.isBefore(now);
    }

    private TodayView empty(String zoneId) {
        return new TodayView(ReviewView.empty(), List.of(), List.of(), PartialView.ok(),
                false, zoneId, productZone.getId());
    }
}
