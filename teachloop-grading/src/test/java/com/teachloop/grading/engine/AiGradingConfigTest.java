package com.teachloop.grading.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.teachloop.grading.ai.LlmClient;
import com.teachloop.grading.domain.AiCallRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** T081：只有 teachloop.grading.engine=ai 才装配真实引擎，默认 stub 不受影响。 */
class AiGradingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiGradingConfig.class)
            .withBean(AiCallRepository.class, () -> mock(AiCallRepository.class))
            .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("engine=ai 时装配 AiGradingEngine 与 DeepSeek 客户端")
    void wiresAiEngineWhenEnabled() {
        runner.withPropertyValues("teachloop.grading.engine=ai", "DEEPSEEK_API_KEY=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context).hasSingleBean(GradingEngine.class);
                    assertThat(context.getBean(GradingEngine.class)).isInstanceOf(AiGradingEngine.class);
                });
    }

    @Test
    @DisplayName("默认（未配置或 stub）不装配 AI 引擎")
    void doesNotWireAiEngineByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(GradingEngine.class);
            assertThat(context).doesNotHaveBean(LlmClient.class);
        });
        runner.withPropertyValues("teachloop.grading.engine=stub")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GradingEngine.class);
                });
    }

    @Test
    @DisplayName("无 Key 时仍可装配（调用时才失败，且不发网络请求）")
    void wiresWithoutApiKey() {
        runner.withPropertyValues("teachloop.grading.engine=ai")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LlmClient.class);
                });
    }
}
