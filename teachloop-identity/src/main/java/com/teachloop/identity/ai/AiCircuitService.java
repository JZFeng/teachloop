package com.teachloop.identity.ai;

import com.teachloop.shared.port.Ports;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-31：熔断状态机。
 *
 * <pre>
 * CLOSED --连续 N 份失败--> OPEN --冷却满--> HALF_OPEN（只放 1 份）
 * HALF_OPEN --成功--> CLOSED；--失败--> OPEN；--试探超时--> OPEN
 * </pre>
 *
 * <p>计数单位是「份」不是「次调用」；任意一份成功清零。OPEN 期间 worker 不再领取新工作项，
 * 所以状态里的 OPEN 不会被并发改写，只有 {@link #acquire} 的冷却选举能翻成 HALF_OPEN。
 */
@Component
public class AiCircuitService implements Ports.AiCircuit {

    private final AiCircuitStateRepository repo;
    private final int failureThreshold;
    private final Duration openDuration;
    private final Duration probeTimeout;

    public AiCircuitService(AiCircuitStateRepository repo,
                            @Value("${teachloop.ai.circuit.failure-threshold:5}") int failureThreshold,
                            @Value("${teachloop.ai.circuit.open-ms:300000}") long openMs,
                            @Value("${teachloop.ai.circuit.probe-timeout-ms:300000}") long probeTimeoutMs) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("teachloop.ai.circuit.failure-threshold 必须 ≥ 1");
        }
        if (openMs < 1 || probeTimeoutMs < 1) {
            throw new IllegalArgumentException("teachloop.ai.circuit 的冷却/试探超时必须 > 0");
        }
        this.repo = repo;
        this.failureThreshold = failureThreshold;
        this.openDuration = Duration.ofMillis(openMs);
        this.probeTimeout = Duration.ofMillis(probeTimeoutMs);
    }

    @Override
    @Transactional
    public int acquire(String providerKey, Instant now) {
        AiCircuitState state = loadOrCreate(providerKey, now);
        if ("OPEN".equals(state.getState())) {
            int elected = repo.electHalfOpen(providerKey, now, now.minus(openDuration));
            return elected == 1 ? 1 : 0;
        }
        if ("HALF_OPEN".equals(state.getState())) {
            Instant deadline = now.minus(probeTimeout);
            if (state.getHalfOpenStartedAt() != null && !state.getHalfOpenStartedAt().isAfter(deadline)) {
                repo.reopenStaleProbe(providerKey, now, deadline);
            }
            return 0;
        }
        return 1;
    }

    @Override
    @Transactional
    public boolean reportSuccess(String providerKey, Instant now) {
        AiCircuitState state = repo.findById(providerKey).orElse(null);
        if (state == null) {
            return false;
        }
        boolean closedFromHalfOpen = false;
        if ("HALF_OPEN".equals(state.getState())) {
            closedFromHalfOpen = true;
            state.setState("CLOSED");
            state.setOpenedAt(null);
            state.setHalfOpenStartedAt(null);
            state.setConsecutiveFailures(0);
            state.setStreakStartedAt(null);
        } else if ("CLOSED".equals(state.getState())) {
            state.setConsecutiveFailures(0);
            state.setStreakStartedAt(null);
        } else {
            // OPEN 期间仍在跑的那份成功不作为试探结论：只有半开试探能关闭熔断。
            return false;
        }
        state.setUpdatedAt(now);
        repo.save(state);
        return closedFromHalfOpen;
    }

    @Override
    @Transactional
    public Ports.CircuitOpenEvent reportFailure(String providerKey, String errorKind, Instant now) {
        AiCircuitState state = loadOrCreate(providerKey, now);
        state.setConsecutiveFailures(state.getConsecutiveFailures() + 1);
        state.setLastErrorKind(errorKind);
        state.setLastFailureAt(now);
        if (state.getStreakStartedAt() == null) {
            state.setStreakStartedAt(now);
        }
        boolean opened = false;
        if ("HALF_OPEN".equals(state.getState())) {
            state.setState("OPEN");
            state.setOpenedAt(now);
            state.setHalfOpenStartedAt(null);
            opened = true;
        } else if ("CLOSED".equals(state.getState()) && state.getConsecutiveFailures() >= failureThreshold) {
            state.setState("OPEN");
            state.setOpenedAt(now);
            opened = true;
        }
        state.setUpdatedAt(now);
        repo.save(state);
        return new Ports.CircuitOpenEvent(opened, state.getOpenedAt(), state.getStreakStartedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Ports.CircuitSnapshot snapshot(String providerKey) {
        return repo.findById(providerKey)
                .map(state -> {
                    Instant nextProbe = "OPEN".equals(state.getState()) && state.getOpenedAt() != null
                            ? state.getOpenedAt().plus(openDuration) : null;
                    return new Ports.CircuitSnapshot(state.getProviderKey(), state.getState(),
                            state.getConsecutiveFailures(), state.getOpenedAt(), nextProbe,
                            state.getLastErrorKind(), state.getStreakStartedAt());
                })
                .orElseGet(() -> new Ports.CircuitSnapshot(providerKey, "CLOSED", 0, null, null, null, null));
    }

    private AiCircuitState loadOrCreate(String providerKey, Instant now) {
        return repo.findById(providerKey).orElseGet(() -> {
            repo.insertIfAbsent(providerKey, now);
            return repo.findById(providerKey)
                    .orElseThrow(() -> new IllegalStateException("熔断状态写入失败: " + providerKey));
        });
    }
}
