package com.teachloop.grading.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.teachloop.grading.ai.DeepSeekLlmClient;
import com.teachloop.grading.ai.LlmClient;
import com.teachloop.grading.ai.RetryingLlmClient;
import com.teachloop.grading.ai.SpringAiLlmClient;
import com.teachloop.grading.domain.AiCallRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** T081：只有 teachloop.grading.engine=ai 才装配真实引擎；客户端默认 Spring AI，可切回 http。 */
class AiGradingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiGradingConfig.class)
            .withBean(AiCallRepository.class, () -> mock(AiCallRepository.class))
            .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("engine=ai 时默认装配 AiGradingEngine + Spring AI ChatClient 客户端")
    void wiresSpringAiEngineByDefault() {
        runner.withPropertyValues("teachloop.grading.engine=ai", "DEEPSEEK_API_KEY=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context.getBean(LlmClient.class)).isInstanceOf(RetryingLlmClient.class);
                    assertThat(((RetryingLlmClient) context.getBean(LlmClient.class)).delegate())
                            .isInstanceOf(SpringAiLlmClient.class);
                    assertThat(context).hasSingleBean(GradingEngine.class);
                    assertThat(context.getBean(GradingEngine.class)).isInstanceOf(AiGradingEngine.class);
                });
    }

    @Test
    @DisplayName("teachloop.ai.client=http 时切回 JDK HttpClient 回退实现")
    void wiresHttpClientWhenConfigured() {
        runner.withPropertyValues("teachloop.grading.engine=ai", "teachloop.ai.client=http",
                        "DEEPSEEK_API_KEY=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context.getBean(LlmClient.class)).isInstanceOf(RetryingLlmClient.class);
                    assertThat(((RetryingLlmClient) context.getBean(LlmClient.class)).delegate())
                            .isInstanceOf(DeepSeekLlmClient.class);
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
    @DisplayName("作文默认比例可配置：70% 能启动，0/100 启动即失败")
    void validatesEssayDefaultPercent() {
        runner.withPropertyValues("teachloop.grading.engine=ai", "DEEPSEEK_API_KEY=test-key",
                        "teachloop.grading.essay-default-rubric.content-percent=70")
                .run(context -> assertThat(context).hasNotFailed());

        runner.withPropertyValues("teachloop.grading.engine=ai", "DEEPSEEK_API_KEY=test-key",
                        "teachloop.grading.essay-default-rubric.content-percent=0")
                .run(context -> assertThat(context).hasFailed());

        runner.withPropertyValues("teachloop.grading.engine=ai", "DEEPSEEK_API_KEY=test-key",
                        "teachloop.grading.essay-default-rubric.content-percent=100")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("无 Key 时仍可装配（调用时才失败，且不发网络请求）")
    void wiresWithoutApiKey() {
        runner.withPropertyValues("teachloop.grading.engine=ai")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context.getBean(LlmClient.class)).isInstanceOf(RetryingLlmClient.class);
                    assertThat(((RetryingLlmClient) context.getBean(LlmClient.class)).delegate())
                            .isInstanceOf(SpringAiLlmClient.class);
                });
    }
}
