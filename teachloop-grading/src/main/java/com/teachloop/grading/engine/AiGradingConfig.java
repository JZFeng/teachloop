package com.teachloop.grading.engine;

import com.teachloop.grading.ai.DeepSeekLlmClient;
import com.teachloop.grading.ai.GradeOutputValidator;
import com.teachloop.grading.ai.LlmClient;
import com.teachloop.grading.domain.AiCallRepository;
import com.teachloop.grading.prompt.PromptBuilder;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 只在 {@code teachloop.grading.engine=ai} 时装配；默认 {@code stub} 的行为保持不变。
 *
 * <p>配置只从属性/环境变量读，不碰 application.yml（由 Lead 统一管理）：
 * <ul>
 *   <li>{@code DEEPSEEK_API_KEY}（无 Key 时 Bean 仍可创建，但调用会立即失败且不发网络请求）</li>
 *   <li>{@code teachloop.ai.base-url}，默认 {@code https://api.deepseek.com}</li>
 *   <li>{@code teachloop.ai.model}，默认 {@code deepseek-flash}</li>
 *   <li>{@code teachloop.ai.temperature}，默认 {@code 0.2}</li>
 *   <li>{@code teachloop.ai.timeout-seconds}，默认 {@code 180}（AC-10.6）</li>
 * </ul>
 */
@Configuration
public class AiGradingConfig {

    @Bean
    @ConditionalOnProperty(name = "teachloop.grading.engine", havingValue = "ai")
    public LlmClient deepSeekLlmClient(
            @Value("${DEEPSEEK_API_KEY:}") String apiKey,
            @Value("${teachloop.ai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${teachloop.ai.model:deepseek-flash}") String model,
            @Value("${teachloop.ai.temperature:0.2}") BigDecimal temperature,
            @Value("${teachloop.ai.timeout-seconds:180}") int timeoutSeconds) {
        return new DeepSeekLlmClient(apiKey, baseUrl, model, temperature, Duration.ofSeconds(timeoutSeconds));
    }

    @Bean
    @ConditionalOnProperty(name = "teachloop.grading.engine", havingValue = "ai")
    public GradingEngine aiGradingEngine(LlmClient llmClient, AiCallRepository aiCalls, Clock clock) {
        return new AiGradingEngine(new PromptBuilder(), new GradeOutputValidator(), llmClient, aiCalls, clock);
    }
}
