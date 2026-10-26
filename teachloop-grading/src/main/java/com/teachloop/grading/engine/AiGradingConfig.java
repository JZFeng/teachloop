package com.teachloop.grading.engine;

import com.teachloop.grading.ai.DeepSeekLlmClient;
import com.teachloop.grading.ai.GradeOutputValidator;
import com.teachloop.grading.ai.LlmCallException;
import com.teachloop.grading.ai.LlmClient;
import com.teachloop.grading.ai.LlmClientProvider;
import com.teachloop.grading.ai.RetryPolicy;
import com.teachloop.grading.ai.RetryingLlmClient;
import com.teachloop.grading.ai.SpringAiLlmClient;
import com.teachloop.grading.domain.AiCallRepository;
import com.teachloop.grading.prompt.EssayRubricWeights;
import com.teachloop.grading.prompt.PromptBuilder;
import com.teachloop.shared.port.Ports;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 只在 {@code teachloop.grading.engine=ai} 时装配；默认 {@code stub} 的行为保持不变。
 *
 * <p>模型客户端二选一（{@code teachloop.ai.client}）：
 * <ul>
 *   <li>{@code spring-ai}（默认）：Spring AI {@code ChatClient}，生产路径</li>
 *   <li>{@code http}：JDK {@code HttpClient} 直连，无 Spring AI 依赖时的回退</li>
 * </ul>
 *
 * <p>其余配置只从属性/环境变量读，不碰 application.yml（由 Lead 统一管理）：
 * <ul>
 *   <li>{@code DEEPSEEK_API_KEY}（无 Key 时 Bean 仍可创建，但调用会立即失败且不发网络请求）</li>
 *   <li>{@code teachloop.ai.base-url}，默认 {@code https://api.deepseek.com}</li>
 *   <li>{@code teachloop.ai.model}，默认 {@code deepseek-flash}</li>
 *   <li>{@code teachloop.ai.temperature}，默认 {@code 0.2}</li>
 *   <li>{@code teachloop.ai.timeout-seconds}，默认 {@code 180}（AC-10.6）</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "teachloop.grading.engine", havingValue = "ai")
public class AiGradingConfig {

    /** D-31：调用层退避重试参数；非法退避串在启动时失败。 */
    @Bean
    public RetryPolicy aiRetryPolicy(
            @Value("${teachloop.ai.retry.max-attempts:2}") int maxAttempts,
            @Value("${teachloop.ai.retry.backoff-ms:2000,8000}") String backoffMs,
            @Value("${teachloop.ai.retry.jitter:0.2}") double jitter,
            @Value("${teachloop.ai.retry.retry-after-max-seconds:30}") long retryAfterMaxSeconds) {
        return RetryPolicy.of(maxAttempts, backoffMs, jitter, retryAfterMaxSeconds);
    }

    @Bean
    @ConditionalOnProperty(name = "teachloop.ai.client", havingValue = "spring-ai", matchIfMissing = true)
    public LlmClient springAiLlmClient(
            @Value("${DEEPSEEK_API_KEY:}") String apiKey,
            @Value("${teachloop.ai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${teachloop.ai.model:deepseek-flash}") String model,
            @Value("${teachloop.ai.temperature:0.2}") BigDecimal temperature,
            @Value("${teachloop.ai.timeout-seconds:180}") int timeoutSeconds,
            ObjectProvider<Ports.AiSettingsLookup> lookup, RetryPolicy retryPolicy) {
        Ports.AiSettings db = configured(lookup);
        if (db != null) {
            apiKey = db.apiKey();
            baseUrl = db.baseUrl();
            model = db.model();
            temperature = db.temperature();
        }
        return RetryingLlmClient.wrap(
                new SpringAiLlmClient(apiKey, baseUrl, model, temperature, Duration.ofSeconds(timeoutSeconds)),
                retryPolicy);
    }

    @Bean
    @ConditionalOnProperty(name = "teachloop.ai.client", havingValue = "http")
    public LlmClient deepSeekLlmClient(
            @Value("${DEEPSEEK_API_KEY:}") String apiKey,
            @Value("${teachloop.ai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${teachloop.ai.model:deepseek-flash}") String model,
            @Value("${teachloop.ai.temperature:0.2}") BigDecimal temperature,
            @Value("${teachloop.ai.timeout-seconds:180}") int timeoutSeconds,
            ObjectProvider<Ports.AiSettingsLookup> lookup, RetryPolicy retryPolicy) {
        Ports.AiSettings db = configured(lookup);
        if (db != null) {
            apiKey = db.apiKey();
            baseUrl = db.baseUrl();
            model = db.model();
            temperature = db.temperature();
        }
        return RetryingLlmClient.wrap(
                new DeepSeekLlmClient(apiKey, baseUrl, model, temperature, Duration.ofSeconds(timeoutSeconds)),
                retryPolicy);
    }

    private static Ports.AiSettings configured(ObjectProvider<Ports.AiSettingsLookup> lookup) {
        Ports.AiSettingsLookup provider = lookup.getIfAvailable();
        if (provider == null) {
            return null; // 单元测试/无 identity 装配时回落环境变量
        }
        Optional<Ports.AiSettings> settings = provider.current();
        return settings.filter(Ports.AiSettings::configured).orElse(null);
    }

    /**
     * D-16：按作业创建者解析 Key（本人 → 全局 → 环境变量兜底），每次判分用短生命周期客户端。
     * 无 Key 时抛 {@link LlmCallException}，由 Worker 标 FAILED，不产生假分。
     */
    @Bean
    public GradingEngine aiGradingEngine(
            AiCallRepository aiCalls, Clock clock,
            @Value("${teachloop.ai.client:spring-ai}") String clientKind,
            @Value("${DEEPSEEK_API_KEY:}") String envApiKey,
            @Value("${teachloop.ai.base-url:https://api.deepseek.com}") String envBaseUrl,
            @Value("${teachloop.ai.model:deepseek-flash}") String envModel,
            @Value("${teachloop.ai.temperature:0.2}") BigDecimal envTemperature,
            @Value("${teachloop.ai.timeout-seconds:180}") int timeoutSeconds,
            @Value("${teachloop.grading.essay-default-rubric.content-percent:80}") int contentPercent,
            ObjectProvider<Ports.AiSettingsLookup> lookup, RetryPolicy retryPolicy) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        // D-28：没有 rubric 的作文按「内容 X% + 语法与拼写 (100-X)%」合成默认评分项
        EssayRubricWeights essayWeights = new EssayRubricWeights(contentPercent);
        LlmClientProvider provider = new LlmClientProvider() {
            @Override
            public LlmClient forTeacher(UUID teacherId) {
                Ports.AiSettings settings = resolve(teacherId);
                // 决策 024：带额外请求头的 provider 必须走 http 客户端——Spring AI 那条链路无法注入任意头。
                if ("http".equalsIgnoreCase(clientKind) || !settings.extraHeaders().isEmpty()) {
                    return RetryingLlmClient.wrap(new DeepSeekLlmClient(settings.apiKey(), settings.baseUrl(),
                            settings.model(), settings.temperature(), timeout, settings.extraHeaders()), retryPolicy);
                }
                return RetryingLlmClient.wrap(new SpringAiLlmClient(settings.apiKey(), settings.baseUrl(),
                        settings.model(), settings.temperature(), timeout), retryPolicy);
            }

            @Override
            public String source(UUID teacherId) {
                try {
                    return resolve(teacherId).source();
                } catch (LlmCallException ex) {
                    return null;
                }
            }

            private Ports.AiSettings resolve(UUID teacherId) {
                Ports.AiSettings settings = lookup.getIfAvailable() == null ? null
                        : lookup.getIfAvailable().resolve(teacherId)
                        .filter(Ports.AiSettings::configured).orElse(null);
                if (settings != null) {
                    return settings;
                }
                if (envApiKey == null || envApiKey.isBlank()) {
                    throw new LlmCallException(
                            "NO_KEY: 未配置 AI 模型，请管理员在 /admin/ai 添加 provider 并选择当前模型");
                }
                return new Ports.AiSettings("deepseek", envBaseUrl, envModel, envApiKey,
                        envTemperature, true, "env");
            }
        };
        return new AiGradingEngine(new PromptBuilder(), new GradeOutputValidator(), provider, aiCalls,
                clock, essayWeights);
    }
}
