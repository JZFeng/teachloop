package com.teachloop.sitting.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 作业的夜间批量批改（决策 003 / T330）。
 *
 * <p>与 {@link GradingDispatchJob}（考试，60 秒扫描）互补：作业不看截止时间，每晚固定时刻把
 * 所有排队中的提交一次性推进队列。时间取 Asia/Shanghai，与 {@link GradingPeakHours} 同一时区口径，
 * 不随部署机器时区漂移；默认 02:30 落在 DeepSeek 夜间折扣时段（00:30–08:30）内。
 *
 * <p>启动补跑：一晚只跑一次，若 02:30 正好在重启，这一晚就会空掉。启动时再跑一次——派发本身是
 * 条件更新 + 幂等入队，重复执行没有副作用。可以用 {@code teachloop.grading.assignment-catch-up=false} 关掉。
 */
@Component
public class AssignmentGradingJob {

    private final OfferingGradingDispatcher dispatcher;
    private final boolean catchUp;

    public AssignmentGradingJob(OfferingGradingDispatcher dispatcher,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${teachloop.grading.assignment-catch-up:true}") boolean catchUp) {
        this.dispatcher = dispatcher;
        this.catchUp = catchUp;
    }

    @Scheduled(cron = "${teachloop.grading.assignment-cron:0 30 2 * * *}",
            zone = "${teachloop.grading.assignment-zone:Asia/Shanghai}")
    public void nightly() {
        dispatcher.dispatchAssignments();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (catchUp) {
            dispatcher.dispatchAssignments();
        }
    }
}
