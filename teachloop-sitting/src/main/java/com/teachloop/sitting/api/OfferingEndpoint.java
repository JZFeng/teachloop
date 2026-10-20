package com.teachloop.sitting.api;

import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.sitting.domain.*;
import com.teachloop.sitting.service.OfferingGradingDispatcher;
import com.teachloop.sitting.service.OfferingWindows;
import com.teachloop.sitting.service.SnapshotCodec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 一场考试 / 一次布置（US-26）。
 *
 * <p>试卷本身不属于班级；布置时一次挂多个班、做内容快照，并允许按班覆盖截止时间与时长。
 */
@RestController
@RequestMapping("/api/offerings")
public class OfferingEndpoint {

    private final OfferingRepository offerings;
    private final OfferingClassRepository offeringClasses;
    private final SubmissionRepository submissions;
    private final Ports.PaperCatalog paperCatalog;
    private final Ports.ClassMembership membership;
    private final Ports.NotificationPort notifications;
    private final SnapshotCodec codec;
    private final OfferingWindows windows;
    private final OfferingGradingDispatcher dispatcher;
    private final AuditPort audit;
    private final Clock clock;

    public OfferingEndpoint(OfferingRepository offerings, OfferingClassRepository offeringClasses,
                            SubmissionRepository submissions, Ports.PaperCatalog paperCatalog,
                            Ports.ClassMembership membership, Ports.NotificationPort notifications,
                            SnapshotCodec codec, OfferingWindows windows,
                            OfferingGradingDispatcher dispatcher, AuditPort audit, Clock clock) {
        this.offerings = offerings;
        this.offeringClasses = offeringClasses;
        this.submissions = submissions;
        this.paperCatalog = paperCatalog;
        this.membership = membership;
        this.notifications = notifications;
        this.codec = codec;
        this.windows = windows;
        this.dispatcher = dispatcher;
        this.audit = audit;
        this.clock = clock;
    }

    /** 布置（T201）：选试卷 → 类型 → 参与班级多选 → 时间窗口 → 按班覆盖 → 发布。 */
    @PostMapping
    @Transactional
    public SittingDtos.OfferingView create(@RequestBody SittingDtos.CreateOffering body,
                                           @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能布置考试");
        }
        UUID paperId = uuid(body.paperId(), "paperId");
        Ports.PaperSnapshot snap = paperCatalog.load(paperId);
        OfferingKind kind = body.kind() == null ? OfferingKind.ASSIGNMENT : enumValue(OfferingKind.class, body.kind());
        TimingMode timing = body.timingMode() == null ? TimingMode.INDIVIDUAL
                : enumValue(TimingMode.class, body.timingMode());

        Instant startAt = parseInstant(body.startAt(), "startAt");
        Instant dueAt = parseInstant(body.dueAt(), "dueAt");
        if (dueAt == null) {
            dueAt = Instant.now(clock).plusSeconds(7 * 86400);
        }
        if (startAt != null && !dueAt.isAfter(startAt)) {
            throw ApiException.badRequest("截止时间必须晚于开始时间");
        }
        int duration = body.durationMinutes() != null ? body.durationMinutes() : snap.durationMinutes();
        if (kind == OfferingKind.EXAM && duration <= 0) {
            throw ApiException.badRequest("考试必须设置时长（可在试卷默认时长上覆盖）");
        }
        if (kind == OfferingKind.EXAM && timing == TimingMode.FIXED_WINDOW && startAt == null) {
            throw ApiException.badRequest("统一开考必须设置开始时间");
        }

        List<SittingDtos.ClassAssignment> assignments = assignments(body);
        if (assignments.isEmpty()) {
            throw ApiException.badRequest("至少要选一个班级");
        }
        Set<UUID> classIds = new LinkedHashSet<>();
        for (SittingDtos.ClassAssignment a : assignments) {
            UUID classId = uuid(a.classId(), "classId");
            if (!classIds.add(classId)) {
                throw ApiException.badRequest("参与班级不能重复");
            }
        }

        var o = new Offering(UUID.randomUUID(), paperId, kind, startAt, dueAt, duration, timing,
                codec.write(snap), snap.paperVersion(), snap.rubricMarkdown(), body.gradingPromptTemplate(),
                Instant.now(clock));
        o.setMaxAttempts(body.maxAttempts() == null ? 3 : body.maxAttempts());
        o.setGraceHours(body.graceHours() == null ? 3 : body.graceHours());
        o.setFeedbackLanguage(body.feedbackLanguage() == null ? "zh-CN" : body.feedbackLanguage());
        o.setShowPeerOverview(body.showPeerOverview() == null || body.showPeerOverview());
        o.setPublishScores(body.publishScores() != null && body.publishScores());
        offerings.save(o);

        for (SittingDtos.ClassAssignment a : assignments) {
            UUID classId = uuid(a.classId(), "classId");
            Instant dueOverride = parseInstant(a.dueAtOverride(), "dueAtOverride");
            if (dueOverride != null) {
                if (startAt != null && !dueOverride.isAfter(startAt)) {
                    throw ApiException.badRequest("按班截止时间必须晚于开始时间");
                }
                if (startAt == null && !dueOverride.isAfter(Instant.now(clock))) {
                    throw ApiException.badRequest("按班截止时间必须晚于当前时间");
                }
            }
            if (a.durationOverride() != null && a.durationOverride() <= 0) {
                throw ApiException.badRequest("按班时长必须大于 0");
            }
            offeringClasses.save(new OfferingClass(UUID.randomUUID(), o.getId(), classId, dueOverride,
                    a.durationOverride()));
        }

        audit.record(new AuditEvent(me.id(), "offering:create", "offering", o.getId().toString(),
                Map.of("classes", assignments.size(), "kind", kind.name(), "timingMode", timing.name())));
        if (Boolean.TRUE.equals(body.publish())) {
            publishInternal(o, me);
        }
        return view(o);
    }

    @GetMapping
    public List<SittingDtos.OfferingView> list(@RequestAttribute("currentUser") CurrentUser me) {
        List<Offering> published = offerings.findByStatusOrderByDueAtDesc(OfferingStatus.PUBLISHED);
        if (me.is("STUDENT")) {
            return published.stream().filter(o -> windows.isParticipant(o, me.id())).map(this::view).toList();
        }
        return published.stream().map(this::view).toList();
    }

    /** 布置详情：参与班级与按班覆盖（T201）。 */
    @GetMapping("/{id}")
    public SittingDtos.OfferingView detail(@PathVariable UUID id,
                                           @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = offerings.findById(id).orElseThrow(() -> ApiException.notFound("布置不存在"));
        if (me.is("STUDENT")) {
            if (o.getStatus() != OfferingStatus.PUBLISHED || !windows.isParticipant(o, me.id())) {
                throw ApiException.notFound("布置不存在");
            }
        }
        return view(o);
    }

    @PostMapping("/{id}/publish")
    @Transactional
    public SittingDtos.OfferingView publish(@PathVariable UUID id,
                                            @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能发布考试");
        }
        Offering o = offerings.findById(id).orElseThrow(() -> ApiException.notFound("布置不存在"));
        publishInternal(o, me);
        return view(o);
    }

    /** 发布成绩（默认关，AC-26.12）：成绩可见性是显式动作，不随发布考试一起打开。 */
    @PostMapping("/{id}/scores/publish")
    @Transactional
    public SittingDtos.OfferingView publishScores(@PathVariable UUID id,
                                                  @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能发布成绩");
        }
        Offering o = offerings.findById(id).orElseThrow(() -> ApiException.notFound("布置不存在"));
        o.setPublishScores(true);
        notifications.notifyScoresPublished(o.getId());
        audit.record(AuditEvent.of(me.id(), "offering:scores_publish", "offering", id.toString()));
        return view(o);
    }

    /**
     * 批改进度（T094 / AC-10.5 / FR-018）：一条聚合 SQL 返回 total/done/running/pending/failed。
     *
     * <p>任何实例都从同一张表聚合，所以多实例结果一致；不依赖长连接、不做 N 次 count。
     * QUEUED（已交卷、还没派发）计进 pending，否则派发前进度会全是 0。
     */
    @GetMapping("/{id}/progress")
    public SittingDtos.ProgressView progress(@PathVariable UUID id) {
        SubmissionRepository.GradingProgress p = submissions.progressOf(id);
        return new SittingDtos.ProgressView((int) p.getTotal(), p.getDone(), p.getRunning(),
                p.getPending(), p.getFailed());
    }

    /**
     * 手动派发（AC-9.3 / US-20「立即派发」）：教师/管理员可用，学生 403。
     * 与自动派发共用条件更新互斥，但不看峰谷时段。
     */
    @PostMapping("/{id}/grading/dispatch")
    public SittingDtos.GradingDispatchView dispatchGrading(@PathVariable UUID id,
                                                           @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能派发批改");
        }
        OfferingGradingDispatcher.DispatchResult result = dispatcher.dispatchNow(id, me.id());
        return new SittingDtos.GradingDispatchView(result.offeringId().toString(),
                result.runId().toString(), result.firstDispatch(), result.enqueued());
    }

    /** 按班统计（T202 / T191 的最小可用面）：按时提交率与迟交份数分开。 */
    @GetMapping("/{id}/class-summary")
    public List<SittingDtos.ClassSummaryView> classSummary(@PathVariable UUID id,
                                                           @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能查看班级汇总");
        }
        Offering o = offerings.findById(id).orElseThrow(() -> ApiException.notFound("布置不存在"));
        List<Submission> all = submissions.findByOfferingIdOrderByCreatedAtDesc(id);
        List<SittingDtos.ClassSummaryView> out = new ArrayList<>();
        for (OfferingClass oc : offeringClasses.findByOfferingId(o.getId())) {
            List<UUID> memberIds = membership.membersOf(oc.getClassId());
            // 同一学生多次尝试时取「最新一次」（attempt_no 最大），不能按 createdAt：
            // 用固定时钟测试时两次尝试的 created_at 可能相同。
            Map<UUID, Submission> latest = new LinkedHashMap<>();
            for (Submission s : all) {
                if (!memberIds.contains(s.getStudentId())) {
                    continue;
                }
                Submission current = latest.get(s.getStudentId());
                if (current == null || s.getAttemptNo() > current.getAttemptNo()) {
                    latest.put(s.getStudentId(), s);
                }
            }
            long submitted = latest.values().stream()
                    .filter(s -> s.getSubmittedAt() != null && s.getRejectedAt() == null).count();
            long onTime = latest.values().stream()
                    .filter(s -> s.getSubmittedAt() != null && s.getRejectedAt() == null && s.getLateBySeconds() == 0)
                    .count();
            long late = submitted - onTime;
            long rejected = all.stream()
                    .filter(s -> memberIds.contains(s.getStudentId()) && s.getRejectedAt() != null).count();
            BigDecimal rate = submitted == 0 ? null
                    : BigDecimal.valueOf(onTime).divide(BigDecimal.valueOf(submitted), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal avg = null;
            if (o.isPublishScores() && submitted > 0) {
                BigDecimal sum = latest.values().stream()
                        .filter(s -> s.getSubmittedAt() != null && s.getRejectedAt() == null)
                        .map(s -> s.getObjectiveTotal().add(s.getSubjectiveTotal()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                avg = sum.divide(BigDecimal.valueOf(submitted), 2, RoundingMode.HALF_UP);
            }
            out.add(new SittingDtos.ClassSummaryView(oc.getClassId().toString(), memberIds.size(), submitted,
                    onTime, late, rejected, rate, avg));
        }
        return out;
    }

    // ---------- internals ----------

    private void publishInternal(Offering o, CurrentUser me) {
        if (o.getStatus() == OfferingStatus.PUBLISHED) {
            return;
        }
        if (offeringClasses.findByOfferingId(o.getId()).isEmpty()) {
            throw ApiException.badRequest("至少要选一个班级");
        }
        if (o.getKind() == OfferingKind.EXAM && o.getTimingMode() == TimingMode.FIXED_WINDOW
                && o.getStartAt() == null) {
            throw ApiException.badRequest("统一开考必须设置开始时间");
        }
        o.setStatus(OfferingStatus.PUBLISHED);
        notifications.notifyOfferingPublished(o.getId());
        audit.record(AuditEvent.of(me.id(), "offering:publish", "offering", o.getId().toString()));
    }

    private SittingDtos.OfferingView view(Offering o) {
        List<SittingDtos.OfferingClassView> classes = offeringClasses.findByOfferingId(o.getId()).stream()
                .map(oc -> new SittingDtos.OfferingClassView(oc.getClassId().toString(),
                        str(oc.getDueAtOverride()), oc.getDurationOverride(),
                        membership.membersOf(oc.getClassId()).size()))
                .toList();
        int submitted = (int) submissions.findByOfferingIdOrderByCreatedAtDesc(o.getId()).stream()
                .filter(s -> s.getSubmittedAt() != null).count();
        return new SittingDtos.OfferingView(o.getId().toString(), o.getPaperId().toString(),
                codec.read(o.getPaperSnapshot()).title(), o.getPaperVersion(), o.getKind().name(),
                o.getTimingMode().name(), o.getStatus().name(), str(o.getStartAt()), o.getDueAt().toString(),
                o.getDurationMinutes(), windows.forOffering(o).durationMinutes(), o.getGraceHours(),
                o.getMaxAttempts(), o.getFeedbackLanguage(), o.isShowPeerOverview(), o.isPublishScores(),
                classes.size(), submitted, classes);
    }

    private static List<SittingDtos.ClassAssignment> assignments(SittingDtos.CreateOffering body) {
        if (body.classes() != null && !body.classes().isEmpty()) {
            return body.classes();
        }
        if (body.classIds() == null) {
            return List.of();
        }
        return body.classIds().stream().map(id -> new SittingDtos.ClassAssignment(id, null, null)).toList();
    }

    private static String str(Instant t) {
        return t == null ? null : t.toString();
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest(field + " 必须是 ISO-8601 时间（如 2026-12-31T23:59:59Z）");
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ex) {
            throw ApiException.badRequest(field + " 不是合法的 UUID");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("不支持的值：" + value);
        }
    }
}
