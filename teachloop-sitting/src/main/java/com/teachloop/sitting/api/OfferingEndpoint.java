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
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 一场考试 / 一次作业（US-26 / D-14）。
 *
 * <p>内容不属于班级；布置时一次挂多个班、做内容快照，考试还允许按班覆盖截止时间与时长。
 * 内容有两种来源：引用试卷（考试）或布置自带（作业，见 {@link #standaloneSnapshot}）。
 */
@RestController
@RequestMapping("/api/offerings")
public class OfferingEndpoint {

    /** 作业没填评分标准时的缺省两条（D-14）：仍给 AI 一条可判分的 rubric，不让它自由发挥。 */
    private static final String DEFAULT_ASSIGNMENT_RUBRIC =
            "# 评分标准\n- 内容要点：切题，观点明确，结构完整。\n- 语言准确性：语法、用词与拼写准确。";

    private final OfferingRepository offerings;
    private final OfferingClassRepository offeringClasses;
    private final SubmissionRepository submissions;
    private final ObjectiveAnswerRepository answers;
    private final Ports.PaperCatalog paperCatalog;
    private final Ports.ClassMembership membership;
    private final Ports.NotificationPort notifications;
    private final Ports.StudentDirectory directory;
    private final SnapshotCodec codec;
    private final OfferingWindows windows;
    private final OfferingGradingDispatcher dispatcher;
    private final AuditPort audit;
    private final Clock clock;
    /** T234：手动「重试失败/卡住项」判定 RUNNING 卡住多久算超时（比定时清扫的 5 分钟短）。 */
    private final long manualStaleMinutes;

    public OfferingEndpoint(OfferingRepository offerings, OfferingClassRepository offeringClasses,
                            SubmissionRepository submissions, ObjectiveAnswerRepository answers,
                            Ports.PaperCatalog paperCatalog,
                            Ports.ClassMembership membership, Ports.NotificationPort notifications,
                            Ports.StudentDirectory directory,
                            SnapshotCodec codec, OfferingWindows windows,
                            OfferingGradingDispatcher dispatcher, AuditPort audit, Clock clock,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${teachloop.grading.manual-stale-minutes:1}") long manualStaleMinutes) {
        this.offerings = offerings;
        this.offeringClasses = offeringClasses;
        this.submissions = submissions;
        this.answers = answers;
        this.paperCatalog = paperCatalog;
        this.membership = membership;
        this.notifications = notifications;
        this.directory = directory;
        this.codec = codec;
        this.windows = windows;
        this.dispatcher = dispatcher;
        this.audit = audit;
        this.clock = clock;
        this.manualStaleMinutes = manualStaleMinutes;
    }

    /**
     * 布置（T201 / D-14）：内容来源由 {@code paperId} 决定——有试卷是考试，没试卷是自带内容的作业；
     * {@code kind} 由来源派生、不由用户选择。选试卷 → 参与班级多选 → 时间窗口 → 按班覆盖 → 发布。
     */
    @PostMapping
    @Transactional
    public SittingDtos.OfferingView create(@RequestBody SittingDtos.CreateOffering body,
                                           @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能布置考试");
        }
        UUID offeringId = UUID.randomUUID();
        UUID paperId = isBlank(body.paperId()) ? null : uuid(body.paperId(), "paperId");
        boolean exam = paperId != null;
        String requestedKind = trimmed(body.kind());
        if (requestedKind != null && exam && !"EXAM".equalsIgnoreCase(requestedKind)) {
            throw ApiException.badRequest("作业不能引用试卷");
        }
        if (requestedKind != null && !exam && !"ASSIGNMENT".equalsIgnoreCase(requestedKind)) {
            throw ApiException.badRequest("考试必须引用试卷");
        }
        OfferingKind kind = exam ? OfferingKind.EXAM : OfferingKind.ASSIGNMENT;
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

        if (!exam) {
            boolean classOverride = assignments.stream()
                    .anyMatch(a -> !isBlank(a.dueAtOverride()) || a.durationOverride() != null);
            if (body.durationMinutes() != null || startAt != null || classOverride
                    || timing != TimingMode.INDIVIDUAL) {
                throw ApiException.badRequest("作业不支持考试计时字段");
            }
        }

        Ports.PaperSnapshot snap = exam ? paperCatalog.load(paperId) : standaloneSnapshot(body, offeringId);
        int duration = exam
                ? (body.durationMinutes() != null ? body.durationMinutes() : snap.durationMinutes())
                : 0;
        if (exam && duration <= 0) {
            throw ApiException.badRequest("考试必须设置时长（可在试卷默认时长上覆盖）");
        }
        if (exam && timing == TimingMode.FIXED_WINDOW && startAt == null) {
            throw ApiException.badRequest("统一开考必须设置开始时间");
        }

        Offering o = new Offering(offeringId, paperId, kind, startAt, dueAt, exam ? duration : null, timing,
                codec.write(snap), snap.paperVersion(), snap.rubricMarkdown(), body.gradingPromptTemplate(),
                Instant.now(clock));
        o.setMaxAttempts(body.maxAttempts() == null ? 3 : body.maxAttempts());
        o.setGraceHours(body.graceHours() == null ? 3 : body.graceHours());
        o.setFeedbackLanguage(body.feedbackLanguage() == null ? "zh-CN" : body.feedbackLanguage());
        o.setShowPeerOverview(body.showPeerOverview() == null || body.showPeerOverview());
        o.setPublishScores(body.publishScores() != null && body.publishScores());
        // 实体自带主键，save() 走 merge 并返回**托管副本**；后面的发布改动必须落在这个副本上，
        // 否则 create 带 publish=true 时接口返回 PUBLISHED、库里却是 DRAFT（2026-10-20 实测）。
        o = offerings.save(o);

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
                Map.of("classes", assignments.size(), "kind", kind.name(), "timingMode", timing.name(),
                        "source", exam ? "paper" : "standalone")));
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
        notifyStudents(o, "scores_published",
                "成绩已发布：" + codec.read(o.getPaperSnapshot()).title(),
                "Scores published: " + codec.read(o.getPaperSnapshot()).title(),
                "老师已发布本次成绩，可查看自己的得分与反馈。",
                "Your teacher published the scores for this sitting. Open it to see your result and feedback.");
        audit.record(AuditEvent.of(me.id(), "offering:scores_publish", "offering", id.toString()));
        return view(o);
    }

    /**
     * 教师端逐份提交表（T182 / T183）：默认按提交时间倒序，未交学生不在本列表内。
     */
    @GetMapping("/{id}/submissions")
    public List<SittingDtos.OfferingSubmissionView> submissions(@PathVariable UUID id,
                                                               @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能查看全班提交表");
        }
        Offering o = offerings.findById(id).orElseThrow(() -> ApiException.notFound("布置不存在"));
        List<Submission> all = submissions.findByOfferingIdOrderByCreatedAtDesc(o.getId());
        Set<UUID> ids = all.stream().map(Submission::getStudentId).collect(java.util.stream.Collectors.toSet());
        var names = directory.displayNames(ids);
        return all.stream().map(s -> new SittingDtos.OfferingSubmissionView(s.getId().toString(),
                s.getStudentId().toString(), names.getOrDefault(s.getStudentId(), s.getStudentId().toString()),
                s.getAttemptNo(), str(s.getSubmittedAt()), s.isAutoSubmitted(), s.getLateBySeconds(),
                str(s.getRejectedAt()), s.getRejectReason(), s.getGradingState().name(),
                s.getObjectiveTotal(), s.getSubjectiveTotal(),
                s.getObjectiveTotal().add(s.getSubjectiveTotal()), s.isActive())).toList();
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

    /**
     * 重试失败项（T182）：只把 FAILED 的提交退回 PENDING，正在跑/已完成的不动。
     * 返回本次重试份数；重复点击幂等。
     */
    @PostMapping("/{id}/grading/retry-failed")
    @Transactional
    public Map<String, Object> retryFailed(@PathVariable UUID id,
                                           @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能重试批改");
        }
        Offering o = offerings.findById(id).orElseThrow(() -> ApiException.notFound("布置不存在"));
        int retried = submissions.retryFailed(o.getId());
        // T234：卡在 RUNNING 的项同样要能手动救回来（定时清扫要等 5 分钟，教师不该干等）
        int reclaimed = submissions.reclaimStaleRunning(o.getId(),
                Instant.now(clock).minus(Duration.ofMinutes(manualStaleMinutes)));
        if (retried > 0 || reclaimed > 0) {
            audit.record(new AuditEvent(me.id(), "grading:retry_failed", "offering", id.toString(),
                    Map.of("count", retried, "reclaimed", reclaimed)));
        }
        return Map.of("offeringId", id.toString(), "retried", retried, "reclaimed", reclaimed);
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
        Ports.PaperSnapshot snap = codec.read(o.getPaperSnapshot());
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
            // T191：未交名单（姓名经 identity 端口取，不跨模块查 users）
            Set<UUID> submittedIds = latest.values().stream()
                    .filter(s -> s.getSubmittedAt() != null && s.getRejectedAt() == null)
                    .map(Submission::getStudentId)
                    .collect(java.util.stream.Collectors.toSet());
            List<UUID> missingIds = memberIds.stream().filter(x -> !submittedIds.contains(x)).toList();
            Map<UUID, String> names = directory.displayNames(missingIds);
            List<SittingDtos.UnsubmittedView> unsubmitted = missingIds.stream()
                    .map(x -> new SittingDtos.UnsubmittedView(x.toString(),
                            names.getOrDefault(x, x.toString())))
                    .toList();

            // 分数分布：按占满分的百分比分桶（跨卷可比）
            long[] buckets = new long[5];
            for (Submission s : latest.values()) {
                if (s.getSubmittedAt() == null || s.getRejectedAt() != null || snap.totalPoints() <= 0) {
                    continue;
                }
                BigDecimal total = s.getObjectiveTotal().add(s.getSubjectiveTotal());
                int percent = total.multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(snap.totalPoints()), 0, RoundingMode.HALF_UP).intValue();
                int idx = percent < 60 ? 0 : percent < 70 ? 1 : percent < 80 ? 2 : percent < 90 ? 3 : 4;
                buckets[Math.max(0, Math.min(4, idx))]++;
            }
            List<SittingDtos.ScoreBucketView> distribution = List.of(
                    new SittingDtos.ScoreBucketView("0–59", buckets[0]),
                    new SittingDtos.ScoreBucketView("60–69", buckets[1]),
                    new SittingDtos.ScoreBucketView("70–79", buckets[2]),
                    new SittingDtos.ScoreBucketView("80–89", buckets[3]),
                    new SittingDtos.ScoreBucketView("90–100", buckets[4]));

            // 逐题正确率：只统计客观题；未答的题本场计 0 作答
            Map<Integer, long[]> itemStats = new LinkedHashMap<>();
            for (Ports.ItemSpec spec : snap.items()) {
                if ("ESSAY".equals(spec.questionType())) {
                    continue;
                }
                itemStats.put(spec.number(), new long[]{0, 0});
            }
            for (Submission s : latest.values()) {
                if (s.getSubmittedAt() == null || s.getRejectedAt() != null) {
                    continue;
                }
                for (ObjectiveAnswer a : answers.findBySubmissionId(s.getId())) {
                    long[] stat = itemStats.get(a.getNumber());
                    if (stat == null) {
                        continue;
                    }
                    stat[0]++;
                    if (a.isCorrect()) {
                        stat[1]++;
                    }
                }
            }
            List<SittingDtos.ItemAccuracyView> itemAccuracy = itemStats.entrySet().stream()
                    .map(e -> {
                        long answered = e.getValue()[0];
                        long correct = e.getValue()[1];
                        BigDecimal itemRate = answered == 0 ? null
                                : BigDecimal.valueOf(correct).multiply(BigDecimal.valueOf(100))
                                        .divide(BigDecimal.valueOf(answered), 2, RoundingMode.HALF_UP);
                        return new SittingDtos.ItemAccuracyView(e.getKey(), answered, correct, itemRate);
                    })
                    .toList();

            out.add(new SittingDtos.ClassSummaryView(oc.getClassId().toString(), memberIds.size(), submitted,
                    onTime, late, rejected, rate, avg, unsubmitted, distribution, itemAccuracy));
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
        offerings.save(o);
        notifications.notifyOfferingPublished(o.getId());
        String paperTitle = codec.read(o.getPaperSnapshot()).title();
        notifyStudents(o, "offering_published",
                "新" + (o.getKind() == OfferingKind.EXAM ? "考试" : "作业") + "：" + paperTitle,
                "New " + (o.getKind() == OfferingKind.EXAM ? "exam" : "assignment") + ": " + paperTitle,
                "老师已布置新的" + (o.getKind() == OfferingKind.EXAM ? "考试" : "作业") + "，请按时完成。",
                "Your teacher published a new " + (o.getKind() == OfferingKind.EXAM ? "exam" : "assignment") + ". Please complete it on time.");
        audit.record(AuditEvent.of(me.id(), "offering:publish", "offering", o.getId().toString()));
    }

    /** 发布类动作给参与班的学生生成站内通知（T114）；接收者由 sitting 解析，不泄漏给其他模块。 */
    private void notifyStudents(Offering o, String kind, String zhTitle, String enTitle,
                                String zhBody, String enBody) {
        Set<UUID> recipients = new java.util.LinkedHashSet<>();
        for (OfferingClass oc : offeringClasses.findByOfferingId(o.getId())) {
            recipients.addAll(membership.membersOf(oc.getClassId()));
        }
        if (recipients.isEmpty()) {
            return;
        }
        boolean en = "en".equalsIgnoreCase(o.getFeedbackLanguage());
        List<Ports.NotificationMessage> messages = recipients.stream()
                .map(id -> new Ports.NotificationMessage(id, kind, "offering", o.getId().toString(),
                        en ? enTitle : zhTitle, en ? enBody : zhBody))
                .toList();
        notifications.notify(messages);
    }

    private SittingDtos.OfferingView view(Offering o) {
        List<SittingDtos.OfferingClassView> classes = offeringClasses.findByOfferingId(o.getId()).stream()
                .map(oc -> new SittingDtos.OfferingClassView(oc.getClassId().toString(),
                        str(oc.getDueAtOverride()), oc.getDurationOverride(),
                        membership.membersOf(oc.getClassId()).size()))
                .toList();
        int submitted = (int) submissions.findByOfferingIdOrderByCreatedAtDesc(o.getId()).stream()
                .filter(s -> s.getSubmittedAt() != null).count();
        // D-14：独立作业没有试卷，paperId 返回 null；标题两种来源都取快照 title。
        return new SittingDtos.OfferingView(o.getId().toString(),
                o.getPaperId() == null ? null : o.getPaperId().toString(),
                codec.read(o.getPaperSnapshot()).title(), o.getPaperVersion(), o.getKind().name(),
                o.getTimingMode().name(), o.getStatus().name(), str(o.getStartAt()), o.getDueAt().toString(),
                o.getDurationMinutes(), windows.forOffering(o).durationMinutes(), o.getGraceHours(),
                o.getMaxAttempts(), o.getFeedbackLanguage(), o.isShowPeerOverview(), o.isPublishScores(),
                classes.size(), submitted, classes);
    }

    /**
     * 作业自带内容的快照（D-14）：题目要求合成为 1 条 ESSAY 题项，rubric 取布置上的评分标准。
     *
     * <p>形状与考试快照同构，所以阅卷、学生端、汇总、导出都不需要分支；
     * {@code paperId} 用本场 id（只作标识，不指向 {@code papers} 表），{@code paperVersion = 0}。
     */
    private static Ports.PaperSnapshot standaloneSnapshot(SittingDtos.CreateOffering body, UUID offeringId) {
        String title = trimmed(body.title());
        if (title == null) {
            throw ApiException.badRequest("作业标题不能为空");
        }
        String instructions = trimmed(body.instructions());
        if (instructions == null) {
            throw ApiException.badRequest("作业题目要求不能为空");
        }
        Integer totalPoints = body.totalPoints();
        if (totalPoints == null || totalPoints < 1 || totalPoints > 1000) {
            throw ApiException.badRequest("作业总分必须是 1 到 1000 之间的整数");
        }
        String rubric = trimmed(body.rubricMarkdown());
        if (rubric == null) {
            rubric = DEFAULT_ASSIGNMENT_RUBRIC;
        }
        Ports.ItemSpec item = new Ports.ItemSpec(UUID.randomUUID(), 1, "ESSAY", null,
                BigDecimal.valueOf(totalPoints), instructions, null, 1, "作业要求", "ALL_OR_NOTHING");
        return new Ports.PaperSnapshot(offeringId, 0, title, totalPoints, 0, List.of(item), rubric);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimmed(String value) {
        return isBlank(value) ? null : value.trim();
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
