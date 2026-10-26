package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.ai.AiProviderEntity;
import com.teachloop.identity.ai.AiProviderRepository;
import com.teachloop.identity.ai.AiSettingsEntity;
import com.teachloop.identity.ai.AiSettingsRepository;
import com.teachloop.shared.port.Ports;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 决策 022 端到端（真实库）：opencode 判分失败 → 熔断 → 自动切到 deepseek → 恢复后自动回切。
 *
 * <p>失败来源是真实的：opencode go 作为判分 provider 缺 {@code x-opencode-session} 会返回
 * 400 MissingSessionID（2026-10-25 实测），所以这里用 400 作为失败类别，
 * 与 worker 拿到非可重试错误时的上报口径一致。
 *
 * <p>不发起真实 LLM 调用：这里验的是「状态机 + 切换 + 审计」，真实连通性由
 * {@code docs/reports/ai-failover-2026-10-25/} 的三端点探测覆盖。
 */
@Testcontainers
@SpringBootTest
class AiFailoverIntegrationTest {

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
        r.add("teachloop.ai.failover.failback-cooldown-ms", () -> "0");
        r.add("teachloop.security.secret", () -> "test-secret-for-ai-failover");
    }

    @Autowired
    Ports.AiCircuit circuit;
    @Autowired
    AiProviderRepository providers;
    @Autowired
    AiSettingsRepository settings;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void opencodeFailsOverToDeepseekThenFailsBack() {
        Instant now = Instant.now();

        // 1) 三条 provider，priority 决定顺序：opencode(10) → deepseek(20) → local(30)
        AiProviderEntity opencode = provider("opencode-go", 10,
                "https://opencode.ai/inference/go/openai/v1", "deepseek-v4.1-flash", now);
        AiProviderEntity deepseek = provider("deepseek", 20,
                "https://api.deepseek.com/v1", "deepseek-chat", now);
        AiProviderEntity local = provider("local", 30,
                "http://192.168.68.101:8080/v1", "qwen3.8-flash-next-iq2_xs", now);
        providers.saveAll(List.of(opencode, deepseek, local));

        // 2) 当前用 opencode
        AiSettingsEntity s = settings.findAll().get(0);
        s.setActiveProviderId(opencode.getId());
        s.setFailoverFromProviderKey(null);
        s.setFailoverReason(null);
        settings.save(s);
        assertThat(currentProviderId()).isEqualTo(opencode.getId());

        // 3) 连续 5 份 400（= worker 对非可重试错误的真实上报）→ 熔断打开 → 切到 deepseek
        for (int i = 1; i <= 5; i++) {
            Ports.CircuitOpenEvent event = circuit.reportFailure("opencode-go", "http_400", now.plusSeconds(i));
            if (i < 5) {
                assertThat(event.opened()).as("第 %s 份还不该打开", i).isFalse();
            } else {
                assertThat(event.opened()).as("第 5 份应打开熔断").isTrue();
            }
        }
        assertThat(currentProviderId()).as("应已切到 deepseek").isEqualTo(deepseek.getId());
        AiSettingsEntity afterSwitch = settings.findAll().get(0);
        assertThat(afterSwitch.getFailoverFromProviderKey()).isEqualTo("opencode-go");
        assertThat(afterSwitch.getFailoverReason()).isEqualTo("circuit_open");
        assertThat(afterSwitch.getActiveModel()).as("换 provider 后模型必须重选").isNull();
        assertThat(auditCount("ai:provider_failover")).isEqualTo(1);
        assertThat(local.getId()).isNotEqualTo(currentProviderId());

        // 4) opencode 恢复（熔断关闭），deepseek 连续成功 3 份 → 自动回切
        jdbc.update("update ai_circuit_state set state='CLOSED', consecutive_failures=0 where provider_key='opencode-go'");
        circuit.acquire("deepseek", now.plusSeconds(50));   // 建熔断行（真实运行时由第一份建立）
        for (int i = 0; i < 3; i++) {
            circuit.reportSuccess("deepseek", now.plusSeconds(60 + i));
        }
        assertThat(currentProviderId()).as("应自动回切 opencode").isEqualTo(opencode.getId());
        assertThat(settings.findAll().get(0).getFailoverReason()).isEqualTo("auto_failback");
        assertThat(auditCount("ai:provider_failback")).isEqualTo(1);
    }

    private UUID currentProviderId() {
        return settings.findAll().get(0).getActiveProviderId();
    }

    private int auditCount(String action) {
        Integer n = jdbc.queryForObject(
                "select count(*) from audit_logs where action = ?", Integer.class, action);
        return n == null ? 0 : n;
    }

    private static AiProviderEntity provider(String key, int priority, String baseUrl, String model, Instant now) {
        AiProviderEntity entity = new AiProviderEntity(UUID.randomUUID(), key, key, baseUrl,
                "openai", null, model, now, now, null);
        entity.setPriority(priority);
        entity.setModelsJson("[\"" + model + "\"]");
        return entity;
    }
}
