package com.teachloop.identity.ai;

import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.port.Ports;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 决策 022：AI provider 故障转移与自动回切。
 *
 * <p>行为约定（与决策 013 的差异写在 022 里）：
 * <ul>
 *   <li><b>只在熔断打开时转移</b>：单份调用失败仍走 013-B 的重试，不切 provider。</li>
 *   <li><b>只影响新派发</b>：已认领正在跑的份跑完，不中断在途调用。</li>
 *   <li><b>自动回切</b>：回到当初被切走的那条（不是「优先级最小的那条」），
 *       要求 ① 距上次切换满冷却 ② 当前 provider 连续成功达阈值 ③ 目标 provider 熔断已关闭。</li>
 *   <li><b>不静默</b>：每次切换写审计 {@code ai:provider_failover} / {@code ai:provider_failback}，
 *       并把 from/to/原因落到 {@code ai_settings}，供 {@code /admin/ai} 展示。</li>
 * </ul>
 */
@Component
public class AiProviderFailoverService implements Ports.AiProviderFailover {

    private static final Logger log = LoggerFactory.getLogger(AiProviderFailoverService.class);

    private final AiSettingsRepository settingsRepo;
    private final AiProviderRepository providers;
    private final AiCircuitStateRepository circuits;
    private final AuditPort audit;
    private final Clock clock;
    private final int failbackSuccessStreak;
    private final Duration failbackCooldown;

    public AiProviderFailoverService(AiSettingsRepository settingsRepo,
                                     AiProviderRepository providers,
                                     AiCircuitStateRepository circuits,
                                     AuditPort audit,
                                     Clock clock,
                                     @Value("${teachloop.ai.failover.failback-success-streak:3}") int failbackSuccessStreak,
                                     @Value("${teachloop.ai.failover.failback-cooldown-ms:600000}") long failbackCooldownMs) {
        if (failbackSuccessStreak < 1) {
            throw new IllegalArgumentException("teachloop.ai.failover.failback-success-streak 必须 ≥ 1");
        }
        if (failbackCooldownMs < 0) {
            throw new IllegalArgumentException("teachloop.ai.failover.failback-cooldown-ms 不能为负");
        }
        this.settingsRepo = settingsRepo;
        this.providers = providers;
        this.circuits = circuits;
        this.audit = audit;
        this.clock = clock;
        this.failbackSuccessStreak = failbackSuccessStreak;
        this.failbackCooldown = Duration.ofMillis(failbackCooldownMs);
    }

    @Override
    @Transactional
    public void onCircuitOpen(String failedProviderKey, Instant now) {
        AiSettingsEntity settings = settings();
        if (settings == null) {
            return;
        }
        Optional<AiProviderEntity> active = activeProvider(settings);
        if (active.isEmpty() || !failedProviderKey.equals(active.get().getProviderKey())) {
            // 不是当前在用的那条：它的熔断不影响派发，不做任何切换。
            return;
        }
        Optional<AiProviderEntity> next = ordered().stream()
                .filter(p -> !p.getProviderKey().equals(failedProviderKey))
                .filter(p -> circuitNotOpen(p.getProviderKey()))
                .findFirst();
        if (next.isEmpty()) {
            // 没有备用：保持熔断打开，等人工处理；这条日志是运维定位的唯一线索。
            log.warn("AI provider {} 熔断打开且没有可用备用 provider，AI 判分将暂停", failedProviderKey);
            return;
        }
        AiProviderEntity target = next.get();
        switchTo(settings, target, failedProviderKey, "circuit_open", now);
        recordAudit("ai:provider_failover", target.getProviderKey(),
                Map.of("from", failedProviderKey,
                        "to", target.getProviderKey(),
                        "reason", "circuit_open",
                        "at", now.toString()));
        log.warn("AI provider 故障转移：{} -> {}（原因 circuit_open）", failedProviderKey, target.getProviderKey());
    }

    @Override
    @Transactional
    public void onGradingSuccess(String providerKey, Instant now) {
        AiSettingsEntity settings = settings();
        if (settings == null || settings.getFailoverFromProviderKey() == null) {
            return;
        }
        Optional<AiProviderEntity> active = activeProvider(settings);
        if (active.isEmpty() || !providerKey.equals(active.get().getProviderKey())) {
            return;
        }
        if (settings.getLastFailoverAt() != null
                && now.isBefore(settings.getLastFailoverAt().plus(failbackCooldown))) {
            return;
        }
        int streak = circuits.findById(providerKey).map(AiCircuitState::getSuccessStreak).orElse(0);
        if (streak < failbackSuccessStreak) {
            return;
        }
        String backTo = settings.getFailoverFromProviderKey();
        Optional<AiProviderEntity> primary = ordered().stream()
                .filter(p -> backTo.equals(p.getProviderKey()))
                .filter(p -> circuitNotOpen(p.getProviderKey()))
                .findFirst();
        if (primary.isEmpty()) {
            // 原 provider 还没恢复，继续用备用。
            return;
        }
        switchTo(settings, primary.get(), providerKey, "auto_failback", now);
        recordAudit("ai:provider_failback", primary.get().getProviderKey(),
                Map.of("from", providerKey,
                        "to", primary.get().getProviderKey(),
                        "reason", "auto_failback",
                        "streak", String.valueOf(streak),
                        "at", now.toString()));
        log.info("AI provider 自动回切：{} -> {}（连续成功 {} 份）",
                providerKey, primary.get().getProviderKey(), streak);
    }

    /** 当前全站是否处于故障转移状态；供管理端展示。 */
    public boolean failoverActive() {
        AiSettingsEntity settings = settings();
        return settings != null && settings.getFailoverFromProviderKey() != null
                && settings.getFailoverReason() != null && !"manual".equals(settings.getFailoverReason());
    }

    private void switchTo(AiSettingsEntity settings, AiProviderEntity target,
                          String fromKey, String reason, Instant now) {
        settings.setActiveProviderId(target.getId());
        // 换 provider 后 model 必须重选：沿用上一条的模型名会 400。
        settings.setActiveModel(null);
        settings.setLastFailoverAt(now);
        settings.setFailoverFromProviderKey(fromKey);
        settings.setFailoverReason(reason);
        settings.setUpdatedAt(now);
        settingsRepo.save(settings);
    }

    private void recordAudit(String action, String targetKey, Map<String, Object> detail) {
        try {
            audit.record(new AuditEvent(null, action, "provider", targetKey, detail));
        } catch (RuntimeException ex) {
            log.warn("AI 故障转移审计写入失败（切换本身已生效）：{}", ex.getMessage());
        }
    }

    private AiSettingsEntity settings() {
        List<AiSettingsEntity> all = settingsRepo.findAll();
        return all.isEmpty() ? null : all.get(0);
    }

    private Optional<AiProviderEntity> activeProvider(AiSettingsEntity settings) {
        if (settings.getActiveProviderId() == null) {
            return Optional.empty();
        }
        return providers.findById(settings.getActiveProviderId());
    }

    private List<AiProviderEntity> ordered() {
        return providers.findAllByOrderByPriorityAscCreatedAtAsc();
    }

    private boolean circuitNotOpen(String providerKey) {
        return circuits.findById(providerKey)
                .map(state -> !"OPEN".equals(state.getState()))
                .orElse(true);
    }

    /** 仅供测试断言使用。 */
    Clock clock() {
        return clock;
    }
}
