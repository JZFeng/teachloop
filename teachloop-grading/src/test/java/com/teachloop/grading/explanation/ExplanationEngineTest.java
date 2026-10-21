package com.teachloop.grading.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.teachloop.grading.ai.LlmCallException;
import com.teachloop.grading.ai.LlmClient;
import com.teachloop.grading.domain.AiCall;
import com.teachloop.grading.domain.AiCallRepository;
import com.teachloop.shared.port.Ports;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** T086：解析 prompt 只用题目信息，不含学生身份与其他学生数据；失败不写字。 */
class ExplanationEngineTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final UUID ITEM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private AiCallRepository aiCalls;

    @BeforeEach
    void setUp() {
        aiCalls = mock(AiCallRepository.class);
    }

    @Test
    @DisplayName("成功：一次调用拿回纯文本，并按 explanation-v1 留痕 ai_calls")
    void generatesTextAndRecordsCall() {
        FakeLlmClient llm = FakeLlmClient.responding(
                new LlmClient.LlmResponse("deepseek-flash", "  B 正确，因为……  ", 120, 40, 640));
        ExplanationEngine engine = engine(llm);

        String text = engine.explain(item("zh-CN"));

        assertThat(text).isEqualTo("B 正确，因为……");
        assertThat(llm.requests).hasSize(1);
        LlmClient.LlmRequest request = llm.requests.get(0);
        // 解析是纯文本：必须关掉判分链路的 JSON 模式，否则学生看到的是 {"解析": "..."}
        assertThat(request.json()).isFalse();
        assertThat(request.system()).contains("纯文本").contains("120");
        assertThat(request.user())
                .contains("Question type: SINGLE_CHOICE")
                .contains("Question: Which one is correct?")
                .contains("Options:")
                .contains("A. wrong")
                .contains("Correct answer: B")
                .contains("Output language: zh-CN");

        ArgumentCaptor<AiCall> saved = ArgumentCaptor.forClass(AiCall.class);
        verify(aiCalls, times(1)).save(saved.capture());
        AiCall call = saved.getValue();
        assertThat(call.getModel()).isEqualTo("deepseek-flash");
        assertThat(call.getPromptVersion()).isEqualTo("explanation-v1");
        assertThat(call.getTokensIn()).isEqualTo(120);
        assertThat(call.getTokensOut()).isEqualTo(40);
        assertThat(call.getLatencyMs()).isEqualTo(640);
        assertThat(call.getRawResponse()).isEqualTo("  B 正确，因为……  ");
        assertThat(call.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("prompt 只含题目信息：不出现学生 id、姓名、客观得分或学生字段名")
    void promptCarriesNoStudentData() {
        UUID studentId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        String studentName = "张三";
        String objectiveScore = "88.5";
        ExplanationEngine engine = engine(FakeLlmClient.responding());

        ExplanationEngine.Prompt prompt = engine.buildPrompt(item("zh-CN"));
        String all = prompt.system() + "\n" + prompt.user();

        assertThat(all)
                .doesNotContain(studentId.toString())
                .doesNotContain(studentName)
                .doesNotContain(objectiveScore)
                .doesNotContain("客观得分")
                .doesNotContain("studentId")
                .doesNotContain("studentName")
                .doesNotContain("student")
                .doesNotContain("score");
        // 允许出现的信息（这是宪法第五条的唯一例外）。
        assertThat(all).contains("Which one is correct?").contains("Correct answer: B");
    }

    @Test
    @DisplayName("英文作业：system prompt 用英文契约，输出语言写 en")
    void usesEnglishPromptForEnglishLanguage() {
        ExplanationEngine engine = engine(FakeLlmClient.responding());

        ExplanationEngine.Prompt prompt = engine.buildPrompt(item("en-US"));

        assertThat(prompt.system()).contains("plain text").contains("120 words");
        assertThat(prompt.user()).contains("Output language: en-US");
    }

    @Test
    @DisplayName("模型调用失败：异常向上抛，不写任何 ai_calls（留给接口计入 failed）")
    void propagatesLlmFailureWithoutRecording() {
        ExplanationEngine engine = engine(FakeLlmClient.failing(new LlmCallException("模型返回 HTTP 500")));

        assertThatThrownBy(() -> engine.explain(item("zh-CN")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("HTTP 500");
        verify(aiCalls, never()).save(any(AiCall.class));
    }

    @Test
    @DisplayName("模型返回空白内容：抛错，不把空串当解析")
    void rejectsBlankContent() {
        ExplanationEngine engine = engine(FakeLlmClient.responding(
                new LlmClient.LlmResponse("deepseek-flash", "   ", 10, 1, 100)));

        assertThatThrownBy(() -> engine.explain(item("zh-CN")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("解析为空");
    }

    @Test
    @DisplayName("AI 引擎未启用（LlmClient 为 null）：抛错且不调用")
    void rejectsWhenEngineDisabled() {
        ExplanationEngine engine = new ExplanationEngine(null, aiCalls, clock());

        assertThatThrownBy(() -> engine.explain(item("zh-CN")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("未启用");
        verify(aiCalls, never()).save(any(AiCall.class));
    }

    private ExplanationEngine engine(FakeLlmClient llm) {
        return new ExplanationEngine(llm, aiCalls, clock());
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static Ports.ExplanationItem item(String language) {
        return new Ports.ExplanationItem(ITEM_ID, 12, "SINGLE_CHOICE",
                "Which one is correct?",
                "A. wrong\nB. right\nC. wrong too\nD. also wrong", "B", 7, language);
    }
}
