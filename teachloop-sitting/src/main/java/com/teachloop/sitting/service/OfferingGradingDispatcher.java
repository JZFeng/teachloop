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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>自动派发只在空闲时段执行（{@link GradingDispatchPolicy}），手动派发任何时刻都能执行。
 * 派发只负责把 QUEUED 变成 PENDING；真正的批改仍由 worker 领取 PENDING 完成。
 */
@Service
public class OfferingGradingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OfferingGradingDispatcher.class);

    private final OfferingRepository offerings;
    private final SubmissionRepository submissions;
    private final Ports.GradingDispatch gradingDispatch;
    private final GradingDispatchPolicy policy;
    private final AuditPort audit;
    private final Clock clock;

    public OfferingGradingDispatcher(OfferingRepository offerings, SubmissionRepository submissions,
                                     Ports.GradingDispatch gradingDispatch, GradingDispatchPolicy policy,
                                     AuditPort audit, Clock clock) {
        this.offerings = offerings;
        this.submissions = submissions;
        this.gradingDispatch = gradingDispatch;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
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

    /**
     * 自动派发（T092）：扫描到期的 PUBLISHED 场次，逐场走条件更新。
     *
     * @return 本次真正完成首次派发的场次数；高峰时段返回 0（等下一次扫描）
     */
    @Transactional
    public int autoDispatch() {
        Instant now = Instant.now(clock);
        if (!policy.mayDispatchNow(now)) {
            log.debug("峰谷调度：{} 处于高峰，自动派发推迟到 {}", now, policy.nextDispatchableAt(now));
            return 0;
        }
        List<UUID> due = offerings.findDueForGrading(now);
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
     * 手动派发（AC-9.3 / US-20「立即派发」）：不受峰谷限制、不受截止时间限制。
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
