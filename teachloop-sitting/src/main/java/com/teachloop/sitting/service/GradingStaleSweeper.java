package com.teachloop.sitting.service;

import com.teachloop.sitting.domain.SubmissionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 僵尸清扫（T091 / plan §7.2）：把「RUNNING 但 started_at 早于阈值」的工作项退回 PENDING。
 *
 * <p>两级触发：应用启动时扫一次（处理上次进程崩溃留下的行），之后每
 * {@code teachloop.grading.sweep-ms}（默认 2 分钟）扫一次。
 *
 * <p>阈值 {@code teachloop.grading.stale-minutes} 默认 5 分钟；单份批改实测 1.8 秒，余量充足。
 * 代价是极小概率重复批一次，换来的是不需要租约、心跳与回收循环。
 *
 * <p>「不重复批在跑的项」由 SQL 条件保证：只回收 {@code grading_started_at < cutoff} 的行，
 * 正在跑的 worker 的 started_at 是刚刚写入的，不会被回收。
 */
@Component
public class GradingStaleSweeper {

    private static final Logger log = LoggerFactory.getLogger(GradingStaleSweeper.class);

    private final SubmissionRepository submissions;
    private final Clock clock;
    private final long staleMinutes;

    public GradingStaleSweeper(SubmissionRepository submissions, Clock clock,
                               @Value("${teachloop.grading.stale-minutes:5}") long staleMinutes) {
        this.submissions = submissions;
        this.clock = clock;
        this.staleMinutes = staleMinutes;
    }

    /** 应用启动后先扫一次：上次进程崩溃时留下的 RUNNING 行在这里被救回来。 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        int recovered = sweep();
        if (recovered > 0) {
            log.warn("启动清扫：{} 个批改工作项从 RUNNING 退回 PENDING", recovered);
        }
    }

    @Scheduled(fixedDelayString = "${teachloop.grading.sweep-ms:120000}",
            initialDelayString = "${teachloop.grading.sweep-ms:120000}")
    @Transactional
    public int sweep() {
        Instant cutoff = Instant.now(clock).minus(Duration.ofMinutes(staleMinutes));
        int recovered = submissions.sweepStaleRunning(cutoff);
        if (recovered > 0) {
            log.warn("定时清扫：{} 个批改工作项从 RUNNING 退回 PENDING（阈值 {} 分钟）",
                    recovered, staleMinutes);
        }
        return recovered;
    }
}
