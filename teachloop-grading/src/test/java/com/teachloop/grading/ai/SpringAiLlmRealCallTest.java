package com.teachloop.grading.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.teachloop.grading.domain.AiCall;
import com.teachloop.grading.domain.AiCallRepository;
import com.teachloop.grading.engine.AiGradingEngine;
import com.teachloop.grading.prompt.PromptBuilder;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T081/T085 真机：真实 Spring AI {@code ChatClient} 调用 DeepSeek，再走输出契约校验。
 *
 * <p>默认被 surefire 的 {@code excludedGroups=ai} 跳过；{@code -Pai} 才执行。
 * 即使被包含，没有 {@code DEEPSEEK_API_KEY} 时用 {@code assumeTrue} 跳过，不报错、不发网络。
 */
@Tag("ai")
class SpringAiLlmRealCallTest {

    @Test
    @DisplayName("真实 ChatClient 返回合规 JSON 并写出 ai_calls")
    void realSpringAiCallReturnsValidGradingResult() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "DEEPSEEK_API_KEY 未配置，跳过真机测试");

        String baseUrl = System.getProperty("teachloop.ai.base-url", "https://api.deepseek.com");
        String model = System.getProperty("teachloop.ai.model", "deepseek-flash");
        AiCallRepository aiCalls = mock(AiCallRepository.class);
        SpringAiLlmClient llm = new SpringAiLlmClient(apiKey, baseUrl, model, new BigDecimal("0.2"),
                Duration.ofSeconds(180));
        AiGradingEngine engine = new AiGradingEngine(new PromptBuilder(), new GradeOutputValidator(), llm,
                aiCalls, Clock.fixed(Instant.now(), ZoneOffset.UTC));

        Ports.GradingResult result = engine.grade(workItem());

        assertThat(result.objectivePoints()).isNull();
        assertThat(result.criteria()).hasSize(2);
        assertThat(result.criteria()).extracting(Ports.CriterionScore::name)
                .containsExactly("内容要点", "语言准确性");
        assertThat(result.subjectivePoints()).isBetween(BigDecimal.ZERO, new BigDecimal("15"));
        assertThat(result.feedback()).isNotBlank();
        verify(aiCalls, atLeastOnce()).save(any(AiCall.class));
    }

    private static Ports.WorkItem workItem() {
        String essay = """
                My class is great because everyone helps each other. Our teacher gives us clear
                instructions and we practise speaking in pairs. Last week we prepared a group
                presentation about festivals. I learned how to organise ideas and speak confidently.
                I would like more time for free reading.
                """;
        return new Ports.WorkItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "测试学生",
                "TEXT", essay, null, List.of(),
                List.of(new Ports.ItemSpec(UUID.randomUUID(), 1, "ESSAY", "SECRET-ANSWER",
                        new BigDecimal("15"), "Write about your class.")),
                "# 评分标准\n- 内容要点（10 分）：是否覆盖题目要求的全部要点。\n"
                + "- 语言准确性（5 分）：语法、用词与句式是否准确得体。\n",
                null, "zh-CN");
    }
}
