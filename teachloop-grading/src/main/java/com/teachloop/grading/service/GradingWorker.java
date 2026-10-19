package com.teachloop.grading.service;

import com.teachloop.grading.domain.GradingRun;
import com.teachloop.grading.domain.Review;
import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.grading.domain.ReviewStatus;
import com.teachloop.grading.domain.GradingRunRepository;
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

/** 工作项就是 submissions 行：条件更新领取，天然幂等。 */
@Component
public class GradingWorker {

    private static final Logger log = LoggerFactory.getLogger(GradingWorker.class);

    private final Ports.SittingLedger ledger;
    private final GradingEngine engine;
    private final GradingRunRepository runs;
    private final ReviewRepository reviews;
    private final Clock clock;

    public GradingWorker(Ports.SittingLedger ledger, GradingEngine engine,
                         GradingRunRepository runs, ReviewRepository reviews, Clock clock) {
        this.ledger = ledger;
        this.engine = engine;
        this.runs = runs;
        this.reviews = reviews;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${teachloop.grading.poll-ms:2000}")
    public void tick() {
        processBatch(5);
    }

    public int processBatch(int limit) {
        List<UUID> waiting = ledger.pendingIds(limit);
        int done = 0;
        for (UUID id : waiting) {
            try {
                Ports.WorkItem item = ledger.load(id);
                ledger.markRunning(id);
                var run = new GradingRun(UUID.randomUUID(), id, "stub", Instant.now(clock));
                runs.save(run);
                Ports.GradingResult result = engine.grade(item);
                ledger.markDone(id, result);
                reviews.findBySubmissionId(id).orElseGet(() -> reviews.save(
                        new Review(UUID.randomUUID(), id, null, ReviewStatus.PENDING, null, Instant.now(clock))));
                run.finish("done", null, Instant.now(clock));
                done++;
            } catch (Exception ex) {
                log.warn("grading failed for {}", id, ex);
                ledger.markFailed(id, ex.getMessage());
            }
        }
        return done;
    }
}
