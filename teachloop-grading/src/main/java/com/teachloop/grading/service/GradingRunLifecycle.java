package com.teachloop.grading.service;

import com.teachloop.grading.domain.GradingRunRepository;
import com.teachloop.shared.port.Ports;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批改 run 的收尾与重开（T335）。
 *
 * <p>一个 offering 只有一条 run，它记录的是「这一场被派发过」。但派发之后提交会陆续进来
 * （重交、迟交、重试失败），所以 run 的状态必须是活的：一场的排队工作做完时收尾
 * （{@code done} + {@code finished_at}），有新工作进队时重新打开。
 *
 * <p>不做这件事的后果不是功能错误，而是运维不可读：库里所有 run 永远停在 {@code running}、
 * {@code finished_at} 永远为空，分不清一次派发到底跑完没有。
 */
@Service
public class GradingRunLifecycle {

    private final GradingRunRepository runs;
    private final Ports.GradingWorkQueue queue;
    private final Clock clock;

    public GradingRunLifecycle(GradingRunRepository runs, Ports.GradingWorkQueue queue, Clock clock) {
        this.runs = runs;
        this.queue = queue;
        this.clock = clock;
    }

    /** worker 跑完一批之后调用：这场没有未完成的工作项了就收尾；还有就什么都不做。 */
    @Transactional
    public void closeIfDrained(UUID offeringId) {
        if (offeringId == null || queue.hasOpenWork(offeringId)) {
            return;
        }
        runs.finishIfRunning(offeringId, Instant.now(clock));
    }

    /** 有新工作进队时调用：已收尾的 run 重新打开（同一场仍然只有一条 run）。 */
    @Transactional
    public void reopen(UUID offeringId) {
        if (offeringId != null) {
            runs.reopenIfDone(offeringId);
        }
    }
}
