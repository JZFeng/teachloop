package com.teachloop.grading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.teachloop.grading.ai.LlmCallException;
import com.teachloop.grading.domain.Review;
import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.grading.engine.GradingEngine;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** D-31：worker 侧的熔断闸门、上报与恢复重派。 */
class GradingWorkerCircuitTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final String PROVIDER = "deepseek";

    private final Ports.SittingLedger ledger = mock(Ports.SittingLedger.class);
    private final Ports.GradingWorkQueue queue = mock(Ports.GradingWorkQueue.class);
    private final GradingEngine engine = mock(GradingEngine.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final GradingRunLifecycle lifecycle = mock(GradingRunLifecycle.class);
    private final Ports.AiCircuit circuit = mock(Ports.AiCircuit.class);
    private final Ports.NotificationPort notifications = mock(Ports.NotificationPort.class);
    private final AuditPort audit = mock(AuditPort.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<Ports.AiSettingsLookup> settings = mock(ObjectProvider.class);
    private final Ports.AiSettingsLookup lookup = mock(Ports.AiSettingsLookup.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final UUID submissionId = UUID.randomUUID();
    private final UUID offeringId = UUID.randomUUID();
    private final UUID teacherId = UUID.randomUUID();

    private GradingWorker worker;

    @BeforeEach
    void setUp() {
        when(settings.getIfAvailable()).thenReturn(lookup);
        when(lookup.current()).thenReturn(Optional.of(new Ports.AiSettings(
                PROVIDER, "https://api.deepseek.com", "deepseek-flash", "key",
                new BigDecimal("0.2"), true, "global")));
        when(reviews.save(any(Review.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queue.pendingIds(anyInt())).thenReturn(List.of());
        worker = new GradingWorker(ledger, queue, engine, reviews, lifecycle,
                circuit, notifications, audit, settings, clock);
    }

    @Test
    @DisplayName("熔断打开时 tick 不领取任何工作项")
    void tickDoesNothingWhenCircuitOpen() {
        when(circuit.acquire(eq(PROVIDER), any())).thenReturn(0);

        worker.tick();

        verify(queue, never()).pendingIds(anyInt());
    }

    @Test
    @DisplayName("半开时只领 1 份试探")
    void tickClaimsSingleProbeWhenHalfOpen() {
        when(circuit.acquire(eq(PROVIDER), any())).thenReturn(1);
        when(queue.pendingIds(1)).thenReturn(List.of());

        worker.tick();

        verify(queue).pendingIds(1);
    }

    @Test
    @DisplayName("一份成功后上报 success；半开关闭时按失败窗口自动重派并通知管理员")
    void successReportsAndRequeuesWhenCircuitCloses() {
        Instant streak = NOW.minusSeconds(600);
        givenOneItem();
        when(engine.grade(any())).thenReturn(new Ports.GradingResult(
                null, new BigDecimal("8"), List.of(), "ok", List.of()));
        when(circuit.snapshot(PROVIDER)).thenReturn(new Ports.CircuitSnapshot(
                PROVIDER, "HALF_OPEN", 5, NOW.minusSeconds(600), null, "SERVER", streak));
        when(circuit.reportSuccess(eq(PROVIDER), any())).thenReturn(true);
        when(queue.requeueCircuitFailed(eq(streak), any())).thenReturn(3);

        int done = worker.processBatch(1);

        assertThat(done).isEqualTo(1);
        verify(queue).markDone(eq(submissionId), any());
        verify(circuit).reportSuccess(eq(PROVIDER), any());
        verify(queue).requeueCircuitFailed(eq(streak), any());
        ArgumentCaptor<Ports.NotificationMessage> adminMessage =
                ArgumentCaptor.forClass(Ports.NotificationMessage.class);
        verify(notifications).notifyAdmins(adminMessage.capture());
        assertThat(adminMessage.getValue().kind()).as("恢复通知走读取侧模板").isEqualTo("ai_circuit_close");
        assertThat(adminMessage.getValue().title()).as("不再写死中文标题").isNull();
        assertThat(adminMessage.getValue().payload())
                .containsEntry("provider", PROVIDER)
                .containsEntry("requeued", "3");
        ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(auditEvent.capture());
        assertThat(auditEvent.getValue().action()).isEqualTo("ai:circuit_close");
    }

    @Test
    @DisplayName("一份失败后上报 failure；触发熔断时通知失败教师与全部管理员并写审计")
    void failureReportsAndNotifiesWhenCircuitOpens() {
        givenOneItem();
        when(engine.grade(any())).thenThrow(
                new LlmCallException("boom", LlmCallException.Kind.SERVER));
        when(circuit.reportFailure(eq(PROVIDER), eq("SERVER"), any())).thenReturn(
                new Ports.CircuitOpenEvent(true, NOW, NOW.minusSeconds(60)));
        when(ledger.teacherIdsWithFailedGradingSince(any())).thenReturn(List.of(teacherId));
        when(circuit.snapshot(PROVIDER)).thenReturn(new Ports.CircuitSnapshot(
                PROVIDER, "OPEN", 5, NOW, NOW.plusSeconds(300), "SERVER", NOW.minusSeconds(60)));

        int done = worker.processBatch(1);

        assertThat(done).isZero();
        verify(queue).markFailed(eq(submissionId), any());
        verify(circuit).reportFailure(eq(PROVIDER), eq("SERVER"), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Ports.NotificationMessage>> teachers = ArgumentCaptor.forClass(List.class);
        verify(notifications).notify(teachers.capture());
        assertThat(teachers.getValue()).singleElement()
                .satisfies(message -> {
                    assertThat(message.recipientId()).isEqualTo(teacherId);
                    assertThat(message.kind()).isEqualTo("ai_circuit_open");
                    assertThat(message.title()).as("不再写死中文标题").isNull();
                    assertThat(message.payload())
                            .containsEntry("provider", PROVIDER)
                            .containsEntry("failures", "5")
                            .containsEntry("errorKind", "SERVER")
                            .containsEntry("nextProbeAt", NOW.plusSeconds(300).toString());
                });
        verify(notifications).notifyAdmins(any());

        ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(auditEvent.capture());
        assertThat(auditEvent.getValue().action()).isEqualTo("ai:circuit_open");
    }

    @Test
    @DisplayName("失败类别优先取 LlmCallException.kind；普通异常沿用原有归类")
    void failureKindUsesStructuredKind() {
        givenOneItem();
        when(engine.grade(any())).thenThrow(
                new LlmCallException("429", LlmCallException.Kind.RATE_LIMIT));
        when(circuit.reportFailure(eq(PROVIDER), eq("RATE_LIMIT"), any())).thenReturn(
                new Ports.CircuitOpenEvent(false, null, null));

        worker.processBatch(1);

        verify(circuit).reportFailure(eq(PROVIDER), eq("RATE_LIMIT"), any());
    }

    private void givenOneItem() {
        when(queue.pendingIds(1)).thenReturn(List.of(submissionId));
        when(reviews.findBySubmissionId(submissionId)).thenReturn(Optional.empty());
        when(ledger.load(submissionId)).thenReturn(new Ports.WorkItem(
                submissionId, offeringId, UUID.randomUUID(), "学生", "rich_text",
                "正文", "<p>正文</p>", List.of(), List.of(), null, null, "zh-CN", teacherId, null, null));
    }
}
