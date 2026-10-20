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
                Ports.WorkItem item = ledger.load(id);
                queue.markRunning(id);
                Ports.GradingResult result = engine.grade(item);
                queue.markDone(id, result);
                // T101：建复核行时捕获 AI 主观草稿。教师改分后的前后对比以这一列为基准；
                // 当前端口没有「读 submission 分数」的方法，所以在这里落库而不是在编辑时再查。
                reviews.findBySubmissionId(id).orElseGet(() -> reviews.save(
                        new Review(UUID.randomUUID(), id, null, ReviewStatus.PENDING, null,
                                result.subjectivePoints(), Instant.now(clock))));
                done++;
            } catch (Exception ex) {
                log.warn("grading failed for {}", id, ex);
                queue.markFailed(id, ex.getMessage());
            }
        }
        return done;
    }
}
