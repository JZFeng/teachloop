package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.shared.port.Ports;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** D-31：熔断状态机——连续 5 份打开、5 分钟后半开只放 1 份、胜负两条恢复路径。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AiCircuitTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
        r.add("teachloop.security.secret", () -> "test-secret-for-ai-circuit");
    }

    private static final Instant T0 = Instant.parse("2026-10-24T00:00:00Z");

    @Autowired
    Ports.AiCircuit circuit;

    @Test
    @DisplayName("连续 5 份失败才打开；OPEN 期间不再放行；5 分钟后只放 1 份试探")
    void opensAfterFiveFailuresAndElectsSingleProbe() {
        String key = "circuit-open";

        for (int i = 1; i <= 4; i++) {
            assertThat(circuit.acquire(key, T0)).as("第 %s 份前仍应放行", i).isEqualTo(1);
            Ports.CircuitOpenEvent event = circuit.reportFailure(key, "SERVER", T0);
            assertThat(event.opened()).as("第 %s 份还不该打开", i).isFalse();
        }
        assertThat(circuit.acquire(key, T0)).isEqualTo(1);
        Ports.CircuitOpenEvent opened = circuit.reportFailure(key, "RATE_LIMIT", T0);
        assertThat(opened.opened()).isTrue();
        assertThat(opened.streakStartedAt()).isEqualTo(T0);

        assertThat(circuit.acquire(key, T0.plusSeconds(10))).isZero();
        Ports.CircuitSnapshot snapshot = circuit.snapshot(key);
        assertThat(snapshot.state()).isEqualTo("OPEN");
        assertThat(snapshot.consecutiveFailures()).isEqualTo(5);
        assertThat(snapshot.nextProbeAt()).isEqualTo(T0.plusSeconds(300));

        // 冷却满：第一个 acquire 抢到唯一试探权，第二个看到 HALF_OPEN 只能等
        Instant later = T0.plusSeconds(301);
        assertThat(circuit.acquire(key, later)).isEqualTo(1);
        assertThat(circuit.acquire(key, later)).isZero();
        assertThat(circuit.snapshot(key).state()).isEqualTo("HALF_OPEN");
    }

    @Test
    @DisplayName("半开试探成功 → CLOSED 且计数清零；之后恢复正常放行")
    void probeSuccessClosesCircuit() {
        String key = "circuit-probe-ok";
        for (int i = 0; i < 5; i++) {
            circuit.reportFailure(key, "SERVER", T0);
        }
        assertThat(circuit.snapshot(key).state()).isEqualTo("OPEN");

        Instant probeAt = T0.plusSeconds(301);
        assertThat(circuit.acquire(key, probeAt)).isEqualTo(1);
        assertThat(circuit.reportSuccess(key, probeAt)).isTrue();

        Ports.CircuitSnapshot closed = circuit.snapshot(key);
        assertThat(closed.state()).isEqualTo("CLOSED");
        assertThat(closed.consecutiveFailures()).isZero();
        assertThat(closed.openedAt()).isNull();
        assertThat(circuit.acquire(key, probeAt.plusSeconds(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("半开试探失败 → 重新 OPEN 并重新计时；连续失败份数继续累计")
    void probeFailureReopensCircuit() {
        String key = "circuit-probe-fail";
        for (int i = 0; i < 5; i++) {
            circuit.reportFailure(key, "SERVER", T0);
        }
        Instant probeAt = T0.plusSeconds(301);
        assertThat(circuit.acquire(key, probeAt)).isEqualTo(1);

        Ports.CircuitOpenEvent reopened = circuit.reportFailure(key, "AUTH", probeAt);
        assertThat(reopened.opened()).isTrue();
        assertThat(reopened.openedAt()).isEqualTo(probeAt);

        Ports.CircuitSnapshot snapshot = circuit.snapshot(key);
        assertThat(snapshot.state()).isEqualTo("OPEN");
        assertThat(snapshot.nextProbeAt()).isEqualTo(probeAt.plusSeconds(300));
        assertThat(circuit.acquire(key, probeAt.plusSeconds(10))).isZero();
    }

    @Test
    @DisplayName("半开试探进程崩溃超过 5 分钟 → 退回 OPEN")
    void staleProbeReturnsToOpen() {
        String key = "circuit-stale-probe";
        for (int i = 0; i < 5; i++) {
            circuit.reportFailure(key, "SERVER", T0);
        }
        Instant probeAt = T0.plusSeconds(301);
        assertThat(circuit.acquire(key, probeAt)).isEqualTo(1);
        assertThat(circuit.snapshot(key).state()).isEqualTo("HALF_OPEN");

        // 试探一直没有结果；超过 probe-timeout 后 acquire 顺手把它退回 OPEN
        assertThat(circuit.acquire(key, probeAt.plusSeconds(301))).isZero();
        assertThat(circuit.snapshot(key).state()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("任意一份成功都清零连续失败数；未知 provider 视为 CLOSED")
    void successResetsStreakAndUnknownProviderIsClosed() {
        String key = "circuit-reset";
        circuit.reportFailure(key, "SERVER", T0);
        circuit.reportFailure(key, "SERVER", T0);
        assertThat(circuit.snapshot(key).consecutiveFailures()).isEqualTo(2);

        assertThat(circuit.reportSuccess(key, T0)).isFalse();
        assertThat(circuit.snapshot(key).consecutiveFailures()).isZero();
        assertThat(circuit.acquire(key, T0)).isEqualTo(1);

        Ports.CircuitSnapshot unknown = circuit.snapshot("never-seen");
        assertThat(unknown.state()).isEqualTo("CLOSED");
        assertThat(unknown.consecutiveFailures()).isZero();
    }
}
