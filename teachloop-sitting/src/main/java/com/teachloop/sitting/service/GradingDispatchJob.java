package com.teachloop.sitting.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期派发的定时外壳（T092）：每分钟扫一次「已发布 + 未派发 + 过了截止 + 宽限期」的场次。
 *
 * <p>并发安全不在这里，而在 {@link OfferingGradingDispatcher} 的条件更新里——
 * 多个实例同时扫到同一场也只有一次 UPDATE 成功。
 */
@Component
public class GradingDispatchJob {

    private final OfferingGradingDispatcher dispatcher;

    public GradingDispatchJob(OfferingGradingDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${teachloop.grading.dispatch-ms:60000}",
            initialDelayString = "${teachloop.grading.dispatch-ms:60000}")
    public void scan() {
        dispatcher.autoDispatch();
    }
}
