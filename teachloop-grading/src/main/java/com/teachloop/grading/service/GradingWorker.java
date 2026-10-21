package com.teachloop.grading.service;

import com.teachloop.grading.domain.Review;
import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.grading.domain.ReviewStatus;
import com.teachloop.grading.engine.GradingEngine;
import com.teachloop.shared.port.Ports;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 工作项就是 submissions 行：条件更新领取，天然幂等。
 *
 * <p>只领 PENDING。QUEUED（已交卷、等派发）不在这里被领走——「一场一条 run」的语义由派发
 * （T092）先把 QUEUED 批量改成 PENDING，worker 才开工。
 *
 * <p>worker 不再为每份提交新建 grading_run：run 是这一场派发的记录，单份执行痕迹交给 T083 的
 * {@code ai_calls}。这样「一场只一条 run」与 {@code grading_runs_offering_uk} 才成立。
 *
 * <p>D-15：AI 只是可选的辅助。复核行上出现 teacherId（老师人工判分或下过结论）或复核已发布时，
 * worker 不领取、不写分、也不用 FAILED 覆盖——人工结果永远优先。
 */
@Component
public class GradingWorker {

    private static final Logger log = LoggerFactory.getLogger(GradingWorker.class);

    private final Ports.SittingLedger ledger;
    private final Ports.GradingWorkQueue queue;
    private final GradingEngine engine;
    private final ReviewRepository reviews;
    private final Clock clock;

    public GradingWorker(Ports.SittingLedger ledger, Ports.GradingWorkQueue queue, GradingEngine engine,
                         ReviewRepository reviews, Clock clock) {
        this.ledger = ledger;
        this.queue = queue;
        this.engine = engine;
        this.reviews = reviews;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${teachloop.grading.poll-ms:2000}")
    public void tick() {
        processBatch(5);
    }

    public int processBatch(int limit) {
        List<UUID> waiting = queue.pendingIds(limit);
        int done = 0;
        for (UUID id : waiting) {
            try {
                // T243 / D-15：老师已经人工判分或复核已发布 → AI 不得再认领这一份。
                if (teacherLocked(id)) {
                    log.info("grading skipped {}: teacher already graded or review published", id);
                    continue;
                }
                Ports.WorkItem item = ledger.load(id);
                queue.markRunning(id);
                log.debug("grading claimed {}", id);
                Ports.GradingResult result = engine.grade(item);
                log.debug("grading graded {}, subjective={}", id, result.subjectivePoints());
                // T243 / D-15：AI 跑的这一小段时间里老师可能已经人工判分；此时丢弃 AI 结果，
                // 不写 markDone——否则 AI 分会覆盖人工分。
                if (teacherLocked(id)) {
                    log.info("grading result discarded {}: teacher graded while AI was running", id);
                    continue;
                }
                queue.markDone(id, result);
                log.debug("grading done {}", id);
                // T101：建复核行时捕获 AI 主观草稿。教师改分后的前后对比以这一列为基准；
                // 当前端口没有「读 submission 分数」的方法，所以在这里落库而不是在编辑时再查。
                // D-15：老师可能先打开过详情（懒建 PENDING、无草稿），AI 完成后补记草稿；
                // 已有教师改分或已发布时 captureAiDraft 不会覆盖。
                Review review = reviews.findBySubmissionId(id).orElse(null);
                if (review == null) {
                    reviews.save(new Review(UUID.randomUUID(), id, null, ReviewStatus.PENDING, null,
                            result.subjectivePoints(), Instant.now(clock)));
                } else if (!review.isPublished()) {
                    review.captureAiDraft(result.subjectivePoints());
                    reviews.save(review);
                }
                done++;
            } catch (Throwable ex) {
                // T234：原来是 catch (Exception)——Error 会被静默跳过，行停在 RUNNING 且没有任何日志。
                log.error("grading failed for {}", id, ex);
                if (teacherLockedOrUnknown(id)) {
                    // T243 / D-15：人工分不能被失败状态覆盖；读不到复核状态时同样不写 FAILED，
                    // 让 T091 的僵尸清扫把它退回 PENDING，下一轮 teacherLocked 仍会拦住。
                    log.warn("grading failed for {} but teacher already graded; not marking failed", id);
                    continue;
                }
                try {
                    queue.markFailed(id, ex.getClass().getSimpleName() + ": " + ex.getMessage());
                } catch (Throwable marking) {
                    log.error("grading failed AND mark-failed failed for {}", id, marking);
                }
            }
        }
        return done;
    }

    /**
     * T243 / D-15：老师的复核结果优先于 AI。
     *
     * <p>判定为「已被老师锁定」的两种情形：复核行上有 teacherId（老师改过分或下过结论），
     * 或复核已发布。两者任一成立，worker 都不得领取、写分或用 FAILED 覆盖。
     */
    private boolean teacherLocked(UUID submissionId) {
        return reviews.findBySubmissionId(submissionId)
                .map(r -> r.getTeacherId() != null || r.isPublished())
                .orElse(false);
    }

    /**
     * 失败路径专用：读不到复核状态时返回 true（视为不可覆盖），把「是否 FAILED」的写入权让给后续清扫与重试。
     */
    private boolean teacherLockedOrUnknown(UUID submissionId) {
        try {
            return teacherLocked(submissionId);
        } catch (Throwable probe) {
            log.error("grading: cannot verify teacher lock for {}, leaving state untouched", submissionId, probe);
            return true;
        }
    }
}
