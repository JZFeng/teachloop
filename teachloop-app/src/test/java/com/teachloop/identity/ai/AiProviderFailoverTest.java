package com.teachloop.identity.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 决策 022：provider 故障转移与自动回切。
 *
 * <p>纯单元测试（不启容器）：只验证「什么时候切、切到谁、写不写审计」这三件事，
 * 熔断状态机本身的行为由 {@code AiCircuitTest} 覆盖。
 */
class AiProviderFailoverTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    private final AiSettingsRepository settingsRepo = mock(AiSettingsRepository.class);
    private final AiProviderRepository providers = mock(AiProviderRepository.class);
    private final AiCircuitStateRepository circuits = mock(AiCircuitStateRepository.class);
    private final List<AuditEvent> audits = new ArrayList<>();
    private final AuditPort audit = audits::add;
    private final java.util.List<com.teachloop.shared.port.Ports.NotificationMessage> notes = new java.util.ArrayList<>();
    private final com.teachloop.shared.port.Ports.NotificationPort notifications =
            new com.teachloop.shared.port.Ports.NotificationPort() {
                @Override
                public void notifyOfferingPublished(java.util.UUID offeringId) {
                }

                @Override
                public void notifyScoresPublished(java.util.UUID offeringId) {
                }

                @Override
                public void notifyAdmins(com.teachloop.shared.port.Ports.NotificationMessage message) {
                    notes.add(message);
                }

                @Override
                public void notify(java.util.List<com.teachloop.shared.port.Ports.NotificationMessage> messages) {
                    notes.addAll(messages);
                }
            };

    private AiSettingsEntity settings;
    private AiProviderEntity primary;
    private AiProviderEntity backup;
    private AiProviderFailoverService service;

    @BeforeEach
    void setUp() {
        settings = new AiSettingsEntity();
        settings.setActiveProviderId(providerId("primary"));
        primary = provider("primary", 10);
        backup = provider("backup", 20);
        service = new AiProviderFailoverService(settingsRepo, providers, circuits, audit, notifications,
                Clock.fixed(NOW, ZoneOffset.UTC), 3, 600_000L);
        when(settingsRepo.findAll()).thenReturn(List.of(settings));
        when(providers.findAllByOrderByPriorityAscCreatedAtAsc()).thenReturn(List.of(primary, backup));
        when(providers.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(providers.findById(backup.getId())).thenReturn(Optional.of(backup));
        when(circuits.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void circuitOpenMovesToNextProviderByPriority() {
        service.onCircuitOpen("primary", NOW);

        assertThat(settings.getActiveProviderId()).isEqualTo(backup.getId());
        assertThat(settings.getFailoverFromProviderKey()).isEqualTo("primary");
        assertThat(settings.getFailoverReason()).isEqualTo("circuit_open");
        assertThat(settings.getLastFailoverAt()).isEqualTo(NOW);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).action()).isEqualTo("ai:provider_failover");
        assertThat(audits.get(0).detail()).containsEntry("to", "backup");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).kind()).isEqualTo("ai_provider_failover");
        assertThat(notes.get(0).payload()).containsEntry("toProvider", "backup");
    }

    @Test
    void circuitOpenWithoutStandbyKeepsCurrentProvider() {
        when(circuits.findById("backup")).thenReturn(Optional.of(circuit("backup", "OPEN", 0)));

        service.onCircuitOpen("primary", NOW);

        assertThat(settings.getActiveProviderId()).isEqualTo(primary.getId());
        assertThat(settings.getFailoverFromProviderKey()).isNull();
        assertThat(audits).isEmpty();
    }

    @Test
    void circuitOpenOnInactiveProviderDoesNothing() {
        service.onCircuitOpen("some-other", NOW);

        assertThat(settings.getActiveProviderId()).isEqualTo(primary.getId());
        assertThat(audits).isEmpty();
    }

    @Test
    void failbackAfterCooldownAndStreak() {
        afterFailover(NOW.minusSeconds(700), 3);

        service.onGradingSuccess("backup", NOW);

        assertThat(settings.getActiveProviderId()).isEqualTo(primary.getId());
        assertThat(settings.getFailoverReason()).isEqualTo("auto_failback");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).action()).isEqualTo("ai:provider_failback");
        assertThat(audits.get(0).detail()).containsEntry("streak", "3");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).kind()).isEqualTo("ai_provider_failback");
    }

    @Test
    void noFailbackBeforeCooldown() {
        afterFailover(NOW.minusSeconds(60), 9);

        service.onGradingSuccess("backup", NOW);

        assertThat(settings.getActiveProviderId()).isEqualTo(backup.getId());
        assertThat(audits).isEmpty();
    }

    @Test
    void noFailbackWhenSuccessStreakTooLow() {
        afterFailover(NOW.minusSeconds(700), 2);

        service.onGradingSuccess("backup", NOW);

        assertThat(settings.getActiveProviderId()).isEqualTo(backup.getId());
        assertThat(audits).isEmpty();
    }

    @Test
    void noFailbackWhenPrimaryStillOpen() {
        afterFailover(NOW.minusSeconds(700), 3);
        when(circuits.findById("primary")).thenReturn(Optional.of(circuit("primary", "OPEN", 0)));

        service.onGradingSuccess("backup", NOW);

        assertThat(settings.getActiveProviderId()).isEqualTo(backup.getId());
        assertThat(audits).isEmpty();
    }

    /** 把状态摆到「已经切到 backup 一段时间」的样子。 */
    private void afterFailover(Instant switchedAt, int backupStreak) {
        settings.setActiveProviderId(backup.getId());
        settings.setFailoverFromProviderKey("primary");
        settings.setFailoverReason("circuit_open");
        settings.setLastFailoverAt(switchedAt);
        when(circuits.findById("backup")).thenReturn(Optional.of(circuit("backup", "CLOSED", backupStreak)));
    }

    private static AiProviderEntity provider(String key, int priority) {
        AiProviderEntity entity = new AiProviderEntity(providerId(key), key, key.toUpperCase(),
                "http://" + key + ".local/v1", "openai", null, key + "-model",
                NOW, NOW, null);
        entity.setPriority(priority);
        return entity;
    }

    private static AiCircuitState circuit(String key, String state, int successStreak) {
        AiCircuitState entity = new AiCircuitState();
        try {
            // providerKey 是主键，实体没有 setter（正常路径由仓库写入）
            java.lang.reflect.Field field = AiCircuitState.class.getDeclaredField("providerKey");
            field.setAccessible(true);
            field.set(entity, key);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        entity.setState(state);
        entity.setSuccessStreak(successStreak);
        entity.setUpdatedAt(NOW);
        return entity;
    }

    private static UUID providerId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
