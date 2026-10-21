package com.teachloop.grading.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.teachloop.grading.domain.AiCall;
import com.teachloop.grading.domain.AiCallRepository;
import com.teachloop.grading.engine.AiGradingEngine;
import com.teachloop.grading.prompt.PromptBuilder;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** T081/T082/T083：模型输出校验、失败重试一次、每次调用留痕。 */
class AiGradingEngineTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    private AiCallRepository aiCalls;

    @BeforeEach
    void setUp() {
        aiCalls = mock(AiCallRepository.class);
    }

    @Test
    @DisplayName("合法 JSON：一次调用即返回结构化分数，并写一条 ai_calls")
    void acceptsValidJson() {
        FakeLlmClient llm = FakeLlmClient.responding(response(VALID_JSON, 1200, 300, 850));
        AiGradingEngine engine = engine(llm);

        Ports.GradingResult result = engine.grade(workItem());

        assertThat(result.objectivePoints()).isNull();
        assertThat(result.subjectivePoints()).isEqualByComparingTo("13");
        assertThat(result.criteria()).extracting(Ports.CriterionScore::name)
                .containsExactly("内容要点", "语言准确性");
        assertThat(result.criteria()).extracting(Ports.CriterionScore::maxPoints)
                .containsExactly(new BigDecimal("10"), new BigDecimal("5"));
        assertThat(result.feedback()).contains("优点：").contains("建议：");
        assertThat(llm.requests).hasSize(1);

        ArgumentCaptor<AiCall> saved = ArgumentCaptor.forClass(AiCall.class);
        verify(aiCalls, times(1)).save(saved.capture());
        AiCall call = saved.getValue();
        assertThat(call.getModel()).isEqualTo("deepseek-flash");
        assertThat(call.getPromptVersion()).isEqualTo("subjective-v2");
        assertThat(call.getTokensIn()).isEqualTo(1200);
        assertThat(call.getTokensOut()).isEqualTo(300);
        assertThat(call.getLatencyMs()).isEqualTo(850);
        assertThat(call.getRawResponse()).isEqualTo(VALID_JSON);
        assertThat(call.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("非法 JSON：重试一次，第二次带纠正提示并成功；两次调用都留痕")
    void retriesOnceAfterInvalidJson() {
        FakeLlmClient llm = FakeLlmClient.responding(
                response("{\"criteria\":[]}", 100, 10, 200),
                response(VALID_JSON, 120, 30, 250));
        AiGradingEngine engine = engine(llm);

        Ports.GradingResult result = engine.grade(workItem());

        assertThat(result.subjectivePoints()).isEqualByComparingTo("13");
        assertThat(llm.requests).hasSize(2);
        assertThat(llm.requests.get(0).user()).doesNotContain("纠正提示");
        assertThat(llm.requests.get(1).user()).contains("纠正提示").contains("上一次输出不合法");
        assertThat(llm.requests.get(1).system()).isEqualTo(llm.requests.get(0).system());

        ArgumentCaptor<AiCall> saved = ArgumentCaptor.forClass(AiCall.class);
        verify(aiCalls, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(AiCall::getRawResponse)
                .containsExactly("{\"criteria\":[]}", VALID_JSON);
        assertThat(saved.getAllValues()).extracting(AiCall::getLatencyMs)
                .containsExactly(200L, 250L);
    }

    @Test
    @DisplayName("两次都非法：抛错（由 Worker 标 failed），原始输出保留在 ai_calls")
    void failsAfterTwoInvalidOutputs() {
        FakeLlmClient llm = FakeLlmClient.responding(
                response("{}", 100, 10, 100),
                response("{\"criteria\":[]}", 110, 20, 150));
        AiGradingEngine engine = engine(llm);

        assertThatThrownBy(() -> engine.grade(workItem()))
                .isInstanceOf(InvalidGradeOutputException.class)
                .hasMessageContaining("连续 2 次输出不合法");

        ArgumentCaptor<AiCall> saved = ArgumentCaptor.forClass(AiCall.class);
        verify(aiCalls, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(AiCall::getRawResponse)
                .containsExactly("{}", "{\"criteria\":[]}");
    }

    @Test
    @DisplayName("项名/顺序不符也算输出不合法，会重试一次")
    void retriesOnCriterionMismatch() {
        String reversed = """
                {"criteria":[{"name":"语言准确性","points":4,"comment":"ok"},
                {"name":"内容要点","points":9,"comment":"ok"}],"total":13,
                "strength":"s","suggestion":"t"}""";
        FakeLlmClient llm = FakeLlmClient.responding(
                response(reversed, 100, 10, 100),
                response(VALID_JSON, 100, 10, 100));
        AiGradingEngine engine = engine(llm);

        Ports.GradingResult result = engine.grade(workItem());

        assertThat(result.subjectivePoints()).isEqualByComparingTo("13");
        assertThat(llm.requests).hasSize(2);
        assertThat(llm.requests.get(1).user()).contains("criteria[0].name 不符");
    }

    @Test
    @DisplayName("ai_calls 写库失败不得掩盖判分结果")
    void repositoryFailureDoesNotHideGradingResult() {
        when(aiCalls.save(any(AiCall.class))).thenThrow(new IllegalStateException("db down"));
        FakeLlmClient llm = FakeLlmClient.responding(response(VALID_JSON, 1, 2, 3));
        AiGradingEngine engine = engine(llm);

        Ports.GradingResult result = engine.grade(workItem());

        assertThat(result.subjectivePoints()).isEqualByComparingTo("13");
    }

    @Test
    @DisplayName("没有主观题时不调用模型、不写 ai_calls，返回 0 分主观结果")
    void skipsModelWhenNoSubjectiveItems() {
        FakeLlmClient llm = FakeLlmClient.responding();
        AiGradingEngine engine = engine(llm);

        Ports.GradingResult result = engine.grade(objectiveOnlyWorkItem());

        assertThat(result.subjectivePoints()).isEqualByComparingTo("0");
        assertThat(result.criteria()).isEmpty();
        assertThat(llm.requests).isEmpty();
        verify(aiCalls, never()).save(any(AiCall.class));
    }

    private AiGradingEngine engine(FakeLlmClient llm) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new AiGradingEngine(new PromptBuilder(), new GradeOutputValidator(), llm, aiCalls, clock);
    }

    private static LlmClient.LlmResponse response(String content, int tokensIn, int tokensOut, long latency) {
        return new LlmClient.LlmResponse("deepseek-flash", content, tokensIn, tokensOut, latency);
    }

    private static final String VALID_JSON = """
            {"criteria":[{"name":"内容要点","points":9,"comment":"正文写到了“My class is great”，并说明每天一起学英语，覆盖了题目要求的要点。"},
            {"name":"语言准确性","points":4,"comment":"使用了“We learn English together every day”，语法基本准确。"}],
            "total":13,"overall":"总评：文章表达了班级学习情况，结构清楚。","strength":"用“We learn English together every day”说明了日常学习。","suggestion":"建议补充一个具体的课堂活动例子。"}""";

    private static Ports.WorkItem workItem() {
        return new Ports.WorkItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "张三",
                "TEXT", "My class is great.\n\nWe learn English together every day.", null,
                List.of(new Ports.AnswerRow(UUID.randomUUID(), 1, "A")),
                List.of(new Ports.ItemSpec(UUID.randomUUID(), 1, "ESSAY", "SECRET",
                        new BigDecimal("15"), "Write about your class.")),
                "# 评分标准\n- 内容要点（10 分）：是否覆盖题目要求的全部要点。\n"
                + "- 语言准确性（5 分）：语法、用词与句式是否准确得体。\n",
                null, "zh-CN");
    }

    private static Ports.WorkItem objectiveOnlyWorkItem() {
        return new Ports.WorkItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "张三",
                "TEXT", "A", null, List.of(new Ports.AnswerRow(UUID.randomUUID(), 1, "A")),
                List.of(new Ports.ItemSpec(UUID.randomUUID(), 1, "SINGLE_CHOICE", "A",
                        new BigDecimal("5"), "Choose one.")),
                "# 评分标准\n- 内容要点（5 分）：...", null, "zh-CN");
    }

    private static final class FakeLlmClient implements LlmClient {

        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private final List<LlmRequest> requests = new ArrayList<>();

        static FakeLlmClient responding(LlmResponse... values) {
            FakeLlmClient client = new FakeLlmClient();
            client.responses.addAll(List.of(values));
            return client;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests.add(request);
            return responses.removeFirst();
        }
    }
}
