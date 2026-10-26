package com.teachloop.grading.service;

import com.teachloop.grading.domain.Review;
import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.grading.domain.ReviewStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teachloop.grading.ai.LlmCallException;
import com.teachloop.grading.engine.GradingEngine;
import com.teachloop.grading.prompt.PromptBuilder;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.port.Ports;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

    /** 正常一轮最多领 5 份；半开试探时 acquire 返回 1。 */
    private static final int BATCH_SIZE = 5;

    private final Ports.SittingLedger ledger;
    private final Ports.GradingWorkQueue queue;
    /** 只用来把逐项意见序列化成 JSON，不参与 Spring 的 JSON 配置。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GradingEngine engine;
    private final ReviewRepository reviews;
    /** T335：一批跑完后把这一场的 run 收尾（有新工作进队时由派发侧重新打开）。 */
    private final GradingRunLifecycle runLifecycle;
    /** D-31：熔断状态；读不到时放行（可用性优先）。 */
    private final Ports.AiCircuit circuit;
    /** D-31：熔断打开/恢复通知。 */
    private final Ports.NotificationPort notifications;
    private final AuditPort audit;
    private final ObjectProvider<Ports.AiSettingsLookup> settings;
    private final Clock clock;

    public GradingWorker(Ports.SittingLedger ledger, Ports.GradingWorkQueue queue, GradingEngine engine,
                         ReviewRepository reviews, GradingRunLifecycle runLifecycle,
                         Ports.AiCircuit circuit, Ports.NotificationPort notifications,
                         AuditPort audit,
                         ObjectProvider<Ports.AiSettingsLookup> settings, Clock clock) {
        this.ledger = ledger;
        this.queue = queue;
        this.engine = engine;
        this.reviews = reviews;
        this.runLifecycle = runLifecycle;
        this.circuit = circuit;
        this.notifications = notifications;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${teachloop.grading.poll-ms:2000}")
    public void tick() {
        int permit = permit();
        if (permit <= 0) {
            // 熔断打开：不领新工作项；已在跑的份会自然跑完。
            log.debug("AI circuit open; skipping this polling round");
            return;
        }
        processBatch(Math.min(permit, 5));
    }

    /** D-31：本轮最多领几份（0 = 熔断中；1 = 半开试探；5 = 正常）。读不到状态时放行。 */
    private int permit() {
        String providerKey = providerKey();
        if (providerKey == null) {
            return BATCH_SIZE;
        }
        try {
            return circuit.acquire(providerKey, Instant.now(clock));
        } catch (RuntimeException ex) {
            log.error("Cannot read AI circuit state; allowing grading (availability first)", ex);
            return BATCH_SIZE;
        }
    }

    public int processBatch(int limit) {
        List<UUID> waiting = queue.pendingIds(limit);
        int done = 0;
        Set<UUID> touchedOfferings = new LinkedHashSet<>();
        for (UUID id : waiting) {
            Ports.WorkItem item = null;
            try {
                // T243 / D-15：老师已经人工判分或复核已发布 → AI 不得再认领这一份。
                if (teacherLocked(id)) {
                    log.info("grading skipped {}: teacher already graded or review published", id);
                    continue;
                }
                item = ledger.load(id);
                queue.markRunning(id);
                log.debug("grading claimed {}", id);
                // 决策 022 D3：记下「这一次」用的是哪条 provider（判分途中可能发生切换）。
                String gradedBy = providerKey();
                Ports.GradingResult result = engine.grade(item);
                log.debug("grading graded {}, subjective={}", id, result.subjectivePoints());
                // T243 / D-15：AI 跑的这一小段时间里老师可能已经人工判分；此时丢弃 AI 结果，
                // 不写 markDone——否则 AI 分会覆盖人工分。
                if (teacherLocked(id)) {
                    log.info("grading result discarded {}: teacher graded while AI was running", id);
                    continue;
                }
                queue.markDone(id, result);
                if (gradedBy != null) {
                    try {
                        queue.markProvider(id, gradedBy);
                    } catch (RuntimeException ex) {
                        // 记录判分者失败不影响成绩本身
                        log.warn("failed to record graded_provider (scores unaffected): {}", ex.getMessage());
                    }
                }
                recordCircuit(true, null);
                log.debug("grading done {}", id);
                // T101：建复核行时捕获 AI 主观草稿。教师改分后的前后对比以这一列为基准；
                // 当前端口没有「读 submission 分数」的方法，所以在这里落库而不是在编辑时再查。
                // D-15：老师可能先打开过详情（懒建 PENDING、无草稿），AI 完成后补记草稿；
                // 已有教师改分或已发布时 captureAiDraft 不会覆盖。
                // T253：AI 的逐项意见与评语也要落库，老师才能看到「AI 为什么给这个分」。
                String criteriaJson = criteriaJson(result.criteria());
                Review review = reviews.findBySubmissionId(id).orElse(null);
                if (review == null) {
                    Review created = new Review(UUID.randomUUID(), id, null, ReviewStatus.PENDING, null,
                            null, Instant.now(clock));
                    created.captureAiDraft(result.subjectivePoints(), criteriaJson, result.feedback(),
                            result.overall(), result.strength(), result.suggestion());
                    created.markAiProvenance(result.model(), result.promptVersion(), result.keySource(), item.feedbackLanguage());
                    reviews.save(created);
                } else if (!review.isPublished()) {
                    review.captureAiDraft(result.subjectivePoints(), criteriaJson, result.feedback(),
                            result.overall(), result.strength(), result.suggestion());
                    review.markAiProvenance(result.model(), result.promptVersion(), result.keySource(), item.feedbackLanguage());
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
                    Review failed = reviews.findBySubmissionId(id).orElseGet(() -> reviews.save(
                            new Review(UUID.randomUUID(), id, null, ReviewStatus.PENDING, null,
                                    null, Instant.now(clock))));
                    if (failed.getTeacherId() == null && !failed.isPublished()) {
                        failed.markAiFailure(classify(ex));
                        reviews.save(failed);
                    }
                } catch (Throwable marking) {
                    log.error("grading failed AND mark-failed failed for {}", id, marking);
                }
                recordCircuit(false, kindOf(ex));
            } finally {
                if (item != null) {
                    touchedOfferings.add(item.offeringId());
                }
            }
        }
        // T335：这一批涉及的场次，如果已经没有排队工作就收尾 run。
        for (UUID offeringId : touchedOfferings) {
            runLifecycle.closeIfDrained(offeringId);
        }
        return done;
    }

    /** 失败分类：复核页要能区分「缺 Key / 鉴权 / 超时 / 输出不合规」。 */
    private static String classify(Throwable ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (message.startsWith("NO_KEY") || message.contains("DEEPSEEK_API_KEY")) {
            return "NO_KEY";
        }
        if (message.contains("401") || message.contains("403")
                || lower.contains("auth") || lower.contains("unauthorized")) {
            return "AUTH_FAILED";
        }
        if (ex instanceof java.net.http.HttpTimeoutException || lower.contains("timeout")
                || lower.contains("timed out") || lower.contains("interrupted")) {
            return "TIMEOUT";
        }
        if (ex instanceof com.teachloop.grading.ai.InvalidGradeOutputException) {
            return "INVALID_OUTPUT";
        }
        return "ERROR";
    }

    /** 把逐项意见序列化成 JSON；序列化失败不影响判分本身。 */
    private String criteriaJson(List<Ports.CriterionScore> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(criteria);
        } catch (JsonProcessingException ex) {
            log.warn("grading criteria serialization failed: {}", ex.getMessage());
            return null;
        }
    }

    /** D-31：每份结束上报熔断状态；失败不影响已落库的判分结果。 */
    private void recordCircuit(boolean success, String errorKind) {
        String providerKey = providerKey();
        if (providerKey == null) {
            return;
        }
        Instant now = Instant.now(clock);
        try {
            if (success) {
                Ports.CircuitSnapshot before = circuit.snapshot(providerKey);
                if (circuit.reportSuccess(providerKey, now)) {
                    onCircuitClosed(providerKey, before.streakStartedAt(), now);
                }
            } else {
                Ports.CircuitOpenEvent event = circuit.reportFailure(providerKey, errorKind, now);
                if (event.opened()) {
                    onCircuitOpened(providerKey, errorKind, event);
                }
            }
        } catch (RuntimeException ex) {
            log.error("AI circuit update failed (grading result unaffected): {}", ex.getMessage(), ex);
        }
    }

    /**
     * 熔断打开：给这一串失败涉及的教师与全部管理员各发一条通知，只发一次。
     *
     * <p>A1（2026-10-25）：文案不再在这里按 {@link java.util.Locale#SIMPLIFIED_CHINESE} 拼死，
     * 只传事实（provider、连续失败份数、最后错误类别、预计探测时间），读取时按界面语言渲染。
     * 这样英文界面不会再收到一条中文熔断通知，也满足 AC-62.5 要求的四项信息。
     */
    private void onCircuitOpened(String providerKey, String errorKind, Ports.CircuitOpenEvent event) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("provider", providerKey);
        String lastErrorKind = errorKind;
        try {
            Ports.CircuitSnapshot snapshot = circuit.snapshot(providerKey);
            if (snapshot != null) {
                payload.put("failures", String.valueOf(snapshot.consecutiveFailures()));
                if (snapshot.nextProbeAt() != null) {
                    payload.put("nextProbeAt", snapshot.nextProbeAt().toString());
                }
                if (lastErrorKind == null) {
                    lastErrorKind = snapshot.lastErrorKind();
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Cannot read AI circuit snapshot for the open notification: {}", ex.getMessage());
        }
        if (lastErrorKind != null) {
            payload.put("errorKind", lastErrorKind);
        }
        try {
            List<UUID> teachers = ledger.teacherIdsWithFailedGradingSince(event.streakStartedAt());
            List<Ports.NotificationMessage> messages = teachers.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .map(teacherId -> new Ports.NotificationMessage(teacherId, "ai_circuit_open",
                            "provider", providerKey, null, null, payload))
                    .toList();
            notifications.notify(messages);
            notifications.notifyAdmins(new Ports.NotificationMessage(null, "ai_circuit_open",
                    "provider", providerKey, null, null, payload));
        } catch (RuntimeException ex) {
            log.warn("AI circuit open notification failed: {}", ex.getMessage());
        }
        try {
            audit.record(new AuditEvent(null, "ai:circuit_open", "provider", providerKey,
                    Map.of("openedAt", String.valueOf(event.openedAt()),
                            "streakStartedAt", String.valueOf(event.streakStartedAt()))));
        } catch (RuntimeException ex) {
            log.warn("AI circuit open audit failed: {}", ex.getMessage());
        }
    }

    /** 熔断关闭：把这一串失败涉及的份自动重派一次，并通知管理员。 */
    private void onCircuitClosed(String providerKey, Instant streakStartedAt, Instant now) {
        int requeued = 0;
        if (streakStartedAt != null) {
            try {
                requeued = queue.requeueCircuitFailed(streakStartedAt, now);
            } catch (RuntimeException ex) {
                log.error("AI circuit auto-requeue failed: {}", ex.getMessage(), ex);
            }
        }
        try {
            audit.record(new AuditEvent(null, "ai:circuit_close", "provider", providerKey,
                    Map.of("requeued", String.valueOf(requeued))));
        } catch (RuntimeException ex) {
            log.warn("AI circuit close audit failed: {}", ex.getMessage());
        }
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("provider", providerKey);
            payload.put("requeued", String.valueOf(requeued));
            notifications.notifyAdmins(new Ports.NotificationMessage(null, "ai_circuit_close",
                    "provider", providerKey, null, null, payload));
        } catch (RuntimeException ex) {
            log.warn("AI circuit close notification failed: {}", ex.getMessage());
        }
    }

    /** 失败类别：优先用 LlmCallException 的结构化 kind，其余沿用原有归类。 */
    private static String kindOf(Throwable ex) {
        if (ex instanceof LlmCallException call) {
            return call.kind().name();
        }
        return classify(ex);
    }

    private String providerKey() {
        Ports.AiSettingsLookup lookup = settings.getIfAvailable();
        if (lookup == null) {
            return null;
        }
        try {
            return lookup.current().map(Ports.AiSettings::provider).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Cannot resolve current AI provider: {}", ex.getMessage());
            return null;
        }
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
