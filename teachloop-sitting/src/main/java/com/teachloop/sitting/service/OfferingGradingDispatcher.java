package com.teachloop.sitting.service;

import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.sitting.domain.Offering;
import com.teachloop.sitting.domain.OfferingRepository;
import com.teachloop.sitting.domain.OfferingStatus;
import com.teachloop.sitting.domain.Submission;
import com.teachloop.sitting.domain.SubmissionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批改派发（T092 / T093）：把「到期、可派发」的场次变成「一条 run + 一批 PENDING 工作项」。
 *
 * <p><b>互斥靠数据库条件更新</b>：{@code UPDATE offerings SET grading_dispatched_at = :now
 * WHERE id = :id AND grading_dispatched_at IS NULL}。两个实例/线程同时派发同一场时，
 * 后到的 UPDATE 会等先到的提交，然后看到 {@code grading_dispatched_at} 已非空而返回 0 行，
 * 于是跳过派发。这比 ShedLock 少一个依赖、少一张锁表，而且跨进程天然一致。
 *
 * <p>顺序是「先条件更新 → 再建 run → 再入队」，三个动作在同一个事务里：条件更新没有成功就
 * 什么都不做；中途失败整体回滚，不会留下「已标记派发但没有 run / 没有队列」的半成品。
 *
 * <p>自动派发到点即派，不看一天中的时段（决策 014 已删除峰谷调度）；手动派发同样任何时刻都能执行。
 * 派发只负责把 QUEUED 变成 PENDING；真正的批改仍由 worker 领取 PENDING 完成。
 */
@Service
public class OfferingGradingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OfferingGradingDispatcher.class);

    private final OfferingRepository offerings;
    private final SubmissionRepository submissions;
    private final Ports.GradingDispatch gradingDispatch;
    private final AuditPort audit;
    private final Clock clock;
    /** AI 未就绪时手动派发必须拦在改状态/入队之前；无 identity 装配（单元测试）时放行。 */
    private final ObjectProvider<Ports.AiSettingsLookup> aiSettings;
    /** 自动派发被 AI 未就绪挡住时的通知出口（管理员 + 相关老师）。 */
    private final Ports.NotificationPort notifications;
    /** 作业/考试标题在内容快照里（offerings 没有 title 列）。 */
    private final SnapshotCodec snapshotCodec;

    /** 通知读侧按 kind 取模板：notification.ai_not_ready.title/body。 */
    private static final String AI_NOT_READY = "ai_not_ready";

    /**
     * 每个「AI 未就绪」周期只通知一次：按老师去重 + 管理员一个全局标志。
     * 扫描每 60 秒一轮，不去重会变成通知风暴；恢复就绪后清空，下一次断供再通知。
     * 多实例部署时每个实例各通知一次（可接受的重复），语义仍由 readiness 决定。
     */
    private final Set<UUID> blockedTeachersNotified = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean blockedAdminsNotified = new AtomicBoolean(false);

    public OfferingGradingDispatcher(OfferingRepository offerings, SubmissionRepository submissions,
                                     Ports.GradingDispatch gradingDispatch,
                                     AuditPort audit, Clock clock,
                                     ObjectProvider<Ports.AiSettingsLookup> aiSettings,
                                     Ports.NotificationPort notifications,
                                     SnapshotCodec snapshotCodec) {
        this.offerings = offerings;
        this.submissions = submissions;
        this.gradingDispatch = gradingDispatch;
        this.audit = audit;
        this.clock = clock;
        this.aiSettings = aiSettings;
        this.notifications = notifications;
        this.snapshotCodec = snapshotCodec;
    }

    /** 一次派发的结果：run 是幂等键，所以重复派发返回同一个 id。 */
    public record DispatchResult(UUID offeringId, UUID runId, boolean firstDispatch, int enqueued) {
    }

    /** 首次派发内部结果：run id + 本次从 QUEUED 转成 PENDING 的份数。 */
    private record FirstDispatch(UUID runId, int enqueued) {
    }

    /** T252：单份派发结果（绿色通道：不等整场派发，先把这一份交给 AI）。 */
    public record SubmissionDispatch(UUID offeringId, UUID submissionId, UUID runId) {
    }

    /** 教师级派发结果：本次派发场次数 + 是否因 AI 未就绪整批跳过（调用方据此决定要不要记「今天跑过了」）。 */
    public record AssignmentDispatch(int dispatched, boolean blockedByAi) {
    }

    /**
     * 自动派发（T092）：扫描到期的 PUBLISHED 场次，逐场走条件更新。
     *
     * <p>不再按峰谷时段推迟：那个规则是 DeepSeek 一家的分时计价，接别的 provider 就不成立，
     * 也不该由代码替管理员决定（决策 014）。
     *
     * @return 本次真正完成首次派发的场次数
     */
    @Transactional
    public int autoDispatch() {
        Instant now = Instant.now(clock);
        List<UUID> due = offerings.findDueForGrading(now);
        // 决策 2026-10-24：AI 未就绪时 quick fail —— 不入队、不改状态，记日志并通知管理员与相关老师。
        // 提交留在 QUEUED：配好模型后的下一轮扫描会自己继续，不必等老师点「重试失败」。
        if (!aiReady()) {
            if (!due.isEmpty()) {
                notifyAiBlocked(due, null);
            }
            return 0;
        }
        int dispatched = 0;
        for (UUID offeringId : due) {
            if (dispatchFirstTime(offeringId, now, null, "auto") != null) {
                dispatched++;
            }
        }
        // 已派发场次的迟到提交也进同一个 run：没有这一条，宽限期后交的作业会永远停在 QUEUED。
        submissions.enqueueQueuedOfDispatched();
        if (dispatched > 0) {
            log.info("自动派发 {} 场考试 / 布置", dispatched);
        }
        return dispatched;
    }

    /**
     * 决策 010：某位教师的固定时间批量派发。不看 due、不看一天中的时段；调用方负责判断“今天是否该跑”。
     *
     * <p>与自动派发共用同一套幂等：首次派发走条件更新建 run，已派发过的只把新到的 QUEUED
     * 补进同一个 run。只处理该教师名下的作业，不跨教师。
     *
     * @return 本次真正完成首次派发的场次数
     */
    @Transactional
    public AssignmentDispatch dispatchAssignmentsForTeacher(UUID teacherId) {
        Instant now = Instant.now(clock);
        List<UUID> pending = offerings.findAssignmentsWithQueuedByTeacher(teacherId);
        if (!aiReady()) {
            if (!pending.isEmpty()) {
                notifyAiBlocked(pending, teacherId);
            }
            return new AssignmentDispatch(0, true);
        }
        int dispatched = 0;
        for (UUID offeringId : pending) {
            if (dispatchFirstTime(offeringId, now, null, "fixed") != null) {
                dispatched++;
                continue;
            }
            gradingDispatch.dispatch(offeringId);
            submissions.enqueueQueued(offeringId);
        }
        submissions.enqueueQueuedOfDispatchedByTeacher(teacherId);
        return new AssignmentDispatch(dispatched, false);
    }

    /**
     * 决策 010：某位教师的 due-date 模式派发。过了 {@code due_at + grace_hours} 就派，
     * 不受时段限制（峰谷调度已删除，见决策 014）。
     *
     * @return 本次真正完成首次派发的场次数
     */
    @Transactional
    public AssignmentDispatch dispatchAssignmentsDueForTeacher(UUID teacherId, Instant now) {
        List<UUID> due = offerings.findDueAssignmentsForGrading(teacherId, now);
        if (!aiReady()) {
            if (!due.isEmpty()) {
                notifyAiBlocked(due, teacherId);
            }
            return new AssignmentDispatch(0, true);
        }
        int dispatched = 0;
        for (UUID offeringId : due) {
            if (dispatchFirstTime(offeringId, now, null, "due") != null) {
                dispatched++;
            }
        }
        // 已派发场次的迟到提交继续进同一个 run。
        submissions.enqueueQueuedOfDispatchedByTeacher(teacherId);
        return new AssignmentDispatch(dispatched, false);
    }

    /**
     * 手动派发（AC-9.3 / US-20「立即派发」）：不受截止时间限制。
     *
     * <p>重复调用是幂等的：已派发的场次返回同一条 run，只把新产生的 QUEUED 提交补进队列。
     */
    @Transactional
    public DispatchResult dispatchNow(UUID offeringId, UUID actorId) {
        Offering offering = offerings.findById(offeringId)
                .orElseThrow(() -> ApiException.notFound("布置不存在"));
        if (offering.getStatus() != OfferingStatus.PUBLISHED) {
            throw ApiException.conflict("只有已发布的布置才能派发批改");
        }
        requireAiReady();
        Instant now = Instant.now(clock);
        FirstDispatch first = dispatchFirstTime(offeringId, now, actorId, "manual");
        if (first != null) {
            return new DispatchResult(offeringId, first.runId(), true, first.enqueued());
        }
        // 已经派发过：run 由 GradingDispatch 幂等返回，队列里新到的 QUEUED 继续入队。
        UUID runId = gradingDispatch.dispatch(offeringId);
        int enqueued = submissions.enqueueQueued(offeringId);
        return new DispatchResult(offeringId, runId, false, enqueued);
    }

    /**
     * T252：单份「让 AI 先批」——老师可以在批改详情里直接把某一份提交交给 AI，
     * 不必等整场派发，也不必先让别人批完。run 仍是按场次幂等创建，只有这一份被推进队列。
     */
    @Transactional
    public SubmissionDispatch dispatchSubmission(UUID offeringId, UUID submissionId, UUID actorId) {
        Offering offering = offerings.findById(offeringId)
                .orElseThrow(() -> ApiException.notFound("布置不存在"));
        if (offering.getStatus() != OfferingStatus.PUBLISHED) {
            throw ApiException.conflict("只有已发布的布置才能派发批改");
        }
        Submission submission = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("error.submission.notFound"));
        if (!submission.getOfferingId().equals(offeringId)) {
            throw ApiException.notFound("error.submission.notFound");
        }
        requireAiReady();
        UUID runId = gradingDispatch.dispatch(offeringId);
        if (submissions.enqueueOne(submissionId) == 0) {
            throw ApiException.conflict("error.grading.dispatchState");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", runId.toString());
        audit.record(new AuditEvent(actorId, "submission:ai_dispatch", "submission",
                submissionId.toString(), detail));
        return new SubmissionDispatch(offeringId, submissionId, runId);
    }

    /**
     * 手动「让 AI 批」的前置闸门：没有可用模型（且引擎不是 stub 替身）时直接 409，
     * 不支持先入队再让 worker 失败——那时界面只剩「失败」，看不出要联系管理员设置 AI。
     * 自动派发与定时作业不走这里（它们的失败由 worker / 熔断处理）。
     */
    private void requireAiReady() {
        if (!aiReady()) {
            throw ApiException.conflict("error.ai.notConfigured");
        }
    }

    /**
     * 现在就绪吗（无 identity 装配 / 读不到就绪状态时放行）。
     *
     * <p>恢复就绪时顺手清空「已通知」标记：下一次 AI 断供要能重新通知一次。
     */
    private boolean aiReady() {
        Ports.AiSettingsLookup lookup = aiSettings.getIfAvailable();
        if (lookup == null) {
            return true;
        }
        Ports.AiReadiness readiness = lookup.readiness();
        if (readiness == null) {
            return true;
        }
        if (readiness.ready()) {
            blockedTeachersNotified.clear();
            blockedAdminsNotified.set(false);
        }
        return readiness.ready();
    }

    /**
     * 自动派发被 AI 未就绪挡住：不入队、不改状态（提交留在 QUEUED），记一条日志，
     * 并给相关老师与全部管理员各发一条站内通知。
     *
     * <p>扫描每 60 秒一轮，所以按「未就绪周期」去重：每个老师 + 管理员各通知一次；
     * 多实例部署时每个实例各通知一次（可接受的重复）。
     */
    private void notifyAiBlocked(List<UUID> offeringIds, UUID teacherId) {
        UUID firstId = offeringIds.get(0);
        Offering first = offerings.findById(firstId).orElse(null);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("title", titleOf(first));
        payload.put("count", String.valueOf(offeringIds.size()));
        // 2026-10-25：老师要知道「还有多少份在等、最早一场什么时候到期」，
        // 所以补 dueAt 与 waiting；取不到就只发原有事实，不能因为统计失败发不出通知。
        try {
            Instant earliestDue = null;
            for (UUID offeringId : offeringIds) {
                Offering offering = offerings.findById(offeringId).orElse(null);
                Instant due = offering == null ? null : offering.getDueAt();
                if (due != null && (earliestDue == null || due.isBefore(earliestDue))) {
                    earliestDue = due;
                }
            }
            if (earliestDue != null) {
                payload.put("dueAt", earliestDue.toString());
            }
            payload.put("waiting", String.valueOf(submissions.countOpenWorkOf(offeringIds)));
        } catch (RuntimeException ex) {
            log.warn("AI not ready notification detail unavailable (offerings={}): {}",
                    offeringIds.size(), ex.getMessage());
        }

        Set<UUID> teachers = new LinkedHashSet<>();
        if (teacherId != null) {
            teachers.add(teacherId);
        }
        for (UUID offeringId : offeringIds) {
            offerings.findById(offeringId).map(Offering::getCreatedBy)
                    .filter(Objects::nonNull).ifPresent(teachers::add);
        }

        List<Ports.NotificationMessage> messages = new ArrayList<>();
        for (UUID recipient : teachers) {
            if (blockedTeachersNotified.add(recipient)) {
                messages.add(new Ports.NotificationMessage(recipient, AI_NOT_READY, "offering",
                        firstId.toString(), null, null, payload));
            }
        }
        boolean adminFirst = blockedAdminsNotified.compareAndSet(false, true);
        if (adminFirst) {
            notifications.notifyAdmins(new Ports.NotificationMessage(null, AI_NOT_READY, "offering",
                    firstId.toString(), null, null, payload));
        }
        if (!messages.isEmpty()) {
            notifications.notify(messages);
        }
        if (!messages.isEmpty() || adminFirst) {
            log.warn("AI not ready: skipped automatic dispatch for {} offering(s) (teachers={}, first={}, "
                            + "firstOfferingId={}); submissions stay QUEUED, the next scan retries after AI is set up",
                    offeringIds.size(), teachers, payload.get("title"), firstId);
        }
    }

    /** 标题只在内容快照里；快照读不出来时用占位符，不让通知因为标题解析失败而发不出去。 */
    private String titleOf(Offering offering) {
        if (offering == null || offering.getPaperSnapshot() == null) {
            return "-";
        }
        try {
            String title = snapshotCodec.read(offering.getPaperSnapshot()).title();
            return title == null || title.isBlank() ? "-" : title;
        } catch (RuntimeException ex) {
            log.debug("cannot read offering snapshot title offering={}", offering.getId(), ex);
            return "-";
        }
    }

    /**
     * 尝试成为「第一次派发」的那一个。
     *
     * @return 首次派发结果；条件更新没抢到（别人已派发）时返回 null
     */
    private FirstDispatch dispatchFirstTime(UUID offeringId, Instant now, UUID actorId, String mode) {
        if (offerings.markGradingDispatched(offeringId, now) != 1) {
            return null;
        }
        UUID runId = gradingDispatch.dispatch(offeringId);
        int enqueued = submissions.enqueueQueued(offeringId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("mode", mode);
        detail.put("runId", runId.toString());
        detail.put("enqueued", enqueued);
        audit.record(new AuditEvent(actorId, "offering:grading_dispatch", "offering",
                offeringId.toString(), detail));
        return new FirstDispatch(runId, enqueued);
    }
}
