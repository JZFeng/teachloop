package com.teachloop.sitting.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到点自动交卷（T199 / AC-26.8）：不依赖学生点提交。
 *
 * <p>扫描周期默认 30 秒，因此归零后最迟 30 秒内定稿（验收要求 60 秒内）。
 * 定稿走条件更新，重复扫描没有副作用。
 */
@Component
public class ExamDeadlineJob {

    private final ExamSessionService sessions;

    public ExamDeadlineJob(ExamSessionService sessions) {
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${teachloop.exam.scan-ms:30000}",
            initialDelayString = "${teachloop.exam.scan-ms:30000}")
    public void scan() {
        sessions.autoSubmitExpired();
    }
}
