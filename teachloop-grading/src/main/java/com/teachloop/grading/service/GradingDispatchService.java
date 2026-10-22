package com.teachloop.grading.service;

import com.teachloop.grading.domain.GradingRun;
import com.teachloop.grading.domain.GradingRunRepository;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批改派发端口实现（T092）：一个 offering 只产生一条 run，重复调用返回同一个 run id。
 *
 * <p>幂等有两层：
 * <ol>
 *   <li>调用方（sitting）先用 {@code offerings.grading_dispatched_at IS NULL} 的条件更新互斥，
 *       同一场只有一个实例会真的走到这里；</li>
 *   <li>这里再用 {@code grading_runs_offering_uk} 唯一索引 + {@code ON CONFLICT DO NOTHING}
 *       兜底：即使直接并发调用端口，也只会插入一条。</li>
 * </ol>
 *
 * <p>run 只记录「这场批改被派发过」，单份提交的执行痕迹属于 T083 的 {@code ai_calls}，
 * 不再为每份 submission 建 run（否则「一场一条 run」不成立）。
 */
@Service
public class GradingDispatchService implements Ports.GradingDispatch {

    private static final String RUN_ENGINE = "offering";

    private final GradingRunRepository runs;
    private final GradingRunLifecycle lifecycle;
    private final Clock clock;

    public GradingDispatchService(GradingRunRepository runs, GradingRunLifecycle lifecycle, Clock clock) {
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID dispatch(UUID offeringId) {
        Instant now = Instant.now(clock);
        runs.insertIfAbsent(UUID.randomUUID(), offeringId, RUN_ENGINE, now);
        // T335：这一场可能上一批已经收尾，又来了新提交（重交 / 迟交 / 重试失败）
        lifecycle.reopen(offeringId);
        return runs.findByOfferingId(offeringId)
                .map(GradingRun::getId)
                .orElseThrow(() -> ApiException.notFound("派发记录未创建：" + offeringId));
    }
}
