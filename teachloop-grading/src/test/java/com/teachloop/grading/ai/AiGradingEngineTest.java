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
import com.teachloop.grading.prompt.EssayRubricWeights;
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
        assertThat(result.feedback())
                .as("单篇作文不加「第 N 题（满分 X 分）」前缀，只有多篇才需要分段")
                .doesNotContain("第 1 题");
        assertThat(llm.requests).hasSize(1);

        ArgumentCaptor<AiCall> saved = ArgumentCaptor.forClass(AiCall.class);
        verify(aiCalls, times(1)).save(saved.capture());
        AiCall call = saved.getValue();
        assertThat(call.getModel()).isEqualTo("deepseek-flash");
        assertThat(call.getPromptVersion()).isEqualTo("subjective-v3");
        assertThat(call.getTokensIn()).isEqualTo(1200);
        assertThat(call.getTokensOut()).isEqualTo(300);
        assertThat(call.getLatencyMs()).isEqualTo(850);
        assertThat(call.getRawResponse()).isEqualTo(VALID_JSON);
        assertThat(call.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("模型把「优点：」「建议：」写进字段：落库前剥掉前缀，组合文本不重复字段名")
    void stripsLabelPrefixesFromModelFields() {
        FakeLlmClient llm = FakeLlmClient.responding(response(LABELED_JSON, 1200, 300, 850));
        AiGradingEngine engine = engine(llm);

        Ports.GradingResult result = engine.grade(workItem());

        assertThat(result.overall()).isEqualTo("文章表达了班级学习情况，结构清楚。");
        assertThat(result.strength()).isEqualTo("用“We learn English together every day”说明了日常学习。");
        assertThat(result.suggestion()).isEqualTo("补充一个具体的课堂活动例子。");
        assertThat(result.feedback())
                .isEqualTo("总评：文章表达了班级学习情况，结构清楚。\n"
                        + "优点：用“We learn English together every day”说明了日常学习。\n"
                        + "建议：补充一个具体的课堂活动例子。");
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
    @DisplayName("两道写作题各用各的 rubric：两次调用、criteria 带题号、总分相加、prompt 不串题")
    void gradesEachEssayWithItsOwnRubric() {
        FakeLlmClient llm = FakeLlmClient.responding(
                response(TWO_ESSAY_FIRST, 100, 20, 100),
                response(TWO_ESSAY_SECOND, 110, 25, 120));
        AiGradingEngine engine = engine(llm);

        Ports.GradingResult result = engine.grade(twoEssayWorkItem(true));

        assertThat(result.subjectivePoints()).isEqualByComparingTo("25");
        assertThat(result.criteria()).extracting(Ports.CriterionScore::itemNumber)
                .containsExactly(15, 15, 16, 16);
        assertThat(llm.requests).hasSize(2);
        assertThat(llm.requests.get(0).system())
                .contains("第 15 题")
                .contains("表达理解")
                .doesNotContain("社团简介")
                .doesNotContain("SECRET-SAMPLE-FIFTEEN");
        assertThat(llm.requests.get(1).system())
                .contains("第 16 题")
                .contains("社团简介")
                .doesNotContain("表达理解")
                .doesNotContain("SECRET-SAMPLE-FIFTEEN");
        assertThat(llm.requests.get(1).user())
                .contains("Our Debate Club meets weekly")
                .doesNotContain("I understand your feeling");
        assertThat(result.feedback()).contains("第 15 题（满分 15 分）").contains("第 16 题（满分 15 分）");
    }

    @Test
    @DisplayName("空白作文：0 分、不调用模型、评语写未作答")
    void blankEssayIsZeroWithoutCall() {
        FakeLlmClient llm = FakeLlmClient.responding(response(TWO_ESSAY_FIRST, 100, 20, 100));
        AiGradingEngine engine = engine(llm);

        Ports.GradingResult result = engine.grade(twoEssayWorkItem(false));

        assertThat(result.subjectivePoints()).isEqualByComparingTo("13");
        assertThat(llm.requests).hasSize(1);
        assertThat(result.feedback()).contains("第 16 题（满分 15 分）：未作答。");
        assertThat(result.criteria()).extracting(Ports.CriterionScore::itemNumber)
                .containsExactly(15, 15);
    }

    @Test
    @DisplayName("没有 rubric：按 80/20 合成「内容 + 语法与拼写」，整个题目要求进 prompt")
    void defaultsToContentAndGrammarSplit() {
        FakeLlmClient llm = FakeLlmClient.responding(response(DEFAULT_ESSAY_JSON, 100, 30, 100));
        AiGradingEngine engine = engine(llm);

        Ports.GradingResult result = engine.grade(essayWithoutRubric());

        assertThat(result.subjectivePoints()).isEqualByComparingTo("15");
        assertThat(result.criteria()).extracting(Ports.CriterionScore::name)
                .containsExactly("内容与任务完成", "语法与拼写");
        assertThat(result.criteria()).extracting(Ports.CriterionScore::maxPoints)
                .containsExactly(new BigDecimal("12"), new BigDecimal("3"));
        assertThat(llm.requests).hasSize(1);
        assertThat(llm.requests.get(0).system())
                .contains("## 题目要求")
                .contains("Write about your summer holiday.")
                .contains("- 内容与任务完成（12 分）")
                .contains("- 语法与拼写（3 分）")
                .contains("满分 12 分")
                .contains("满分 3 分");
    }

    @Test
    @DisplayName("默认比例可配置：70% 内容 → 11 + 4（10.5 四舍五入）")
    void usesConfiguredWeightForDefaultRubric() {
        FakeLlmClient llm = FakeLlmClient.responding(response(DEFAULT_ESSAY_JSON_70, 100, 30, 100));
        AiGradingEngine engine = engine(llm, new EssayRubricWeights(70));

        Ports.GradingResult result = engine.grade(essayWithoutRubric());

        assertThat(result.criteria()).extracting(Ports.CriterionScore::maxPoints)
                .containsExactly(new BigDecimal("11"), new BigDecimal("4"));
        assertThat(llm.requests.get(0).system())
                .contains("- 内容与任务完成（11 分）")
                .contains("- 语法与拼写（4 分）");
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

    private static final String TWO_ESSAY_FIRST = """
            {"criteria":[{"name":"表达理解","points":5,"comment":"正文写了“I understand your feeling”，直接回应了朋友的处境。"},
            {"name":"给出建议","points":8,"comment":"建议“talk with your parents calmly”，给出了可执行的下一步。"}],
            "total":13,"overall":"回信结构完整，语气得体。","strength":"用“talk with your parents calmly”给出具体建议。","suggestion":"补充一句对父母立场的理解。"}""";

    private static final String TWO_ESSAY_SECOND = """
            {"criteria":[{"name":"社团简介","points":5,"comment":"正文用“Our Debate Club”点明社团，简介清楚。"},
            {"name":"社团活动","points":7,"comment":"写了“meets weekly to discuss public topics”，活动安排具体。"}],
            "total":12,"overall":"讲稿有号召力，信息完整。","strength":"用“meets weekly”说明了固定活动。","suggestion":"补一句欢迎新生体验的建议。"}""";

    private static Ports.WorkItem twoEssayWorkItem(boolean answerSecond) {
        List<Ports.AnswerRow> answers = new java.util.ArrayList<>();
        answers.add(new Ports.AnswerRow(UUID.randomUUID(), 15,
                "Dear Chen Ming, I understand your feeling and suggest you talk with your parents calmly."));
        if (answerSecond) {
            answers.add(new Ports.AnswerRow(UUID.randomUUID(), 16,
                    "Good morning, everyone! Our Debate Club meets weekly to discuss public topics."));
        }
        List<Ports.ItemSpec> items = List.of(
                new Ports.ItemSpec(UUID.randomUUID(), 15, "ESSAY", null, new BigDecimal("15"),
                        "Write a reply letter.", null, 4, "第四节 写作", "ALL_OR_NOTHING",
                        "# 评分标准\n- 表达理解（5 分）：是否回应了朋友的处境。\n"
                                + "- 给出建议（8 分）：建议是否具体可行。\n"),
                new Ports.ItemSpec(UUID.randomUUID(), 16, "ESSAY", null, new BigDecimal("15"),
                        "Write a speech.", null, 4, "第四节 写作", "ALL_OR_NOTHING",
                        "# 评分标准\n- 社团简介（5 分）：是否点明社团。\n"
                                + "- 社团活动（7 分）：活动安排是否具体。\n"));
        return new Ports.WorkItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "张三",
                "TEXT", null, null, answers, items,
                "# 试卷默认评分标准\n- 总评（15 分）：只按本题 rubric。\n", null, "zh-CN");
    }

    private static final String DEFAULT_ESSAY_JSON = """
            {"criteria":[{"name":"内容与任务完成","points":12,"comment":"正文写了“My class is great”，并说明每天一起学英语，覆盖了题目要求的要点。"},
            {"name":"语法与拼写","points":3,"comment":"使用了“We learn English together every day”，语法与拼写基本正确。"}],
            "total":15,"overall":"文章完整回应了题目，语言基本准确。","strength":"用“My class is great”直接点题。","suggestion":"补充一个具体的课堂活动例子。"}""";

    private static final String DEFAULT_ESSAY_JSON_70 = """
            {"criteria":[{"name":"内容与任务完成","points":11,"comment":"正文写了“My class is great”，并说明每天一起学英语，覆盖了题目要求的要点。"},
            {"name":"语法与拼写","points":4,"comment":"使用了“We learn English together every day”，语法与拼写基本正确。"}],
            "total":15,"overall":"文章完整回应了题目，语言基本准确。","strength":"用“My class is great”直接点题。","suggestion":"补充一个具体的课堂活动例子。"}""";

    /** 没有 rubric 的考试作文：正文在 contentText，题项与试卷都没有评分标准。 */
    private static Ports.WorkItem essayWithoutRubric() {
        return new Ports.WorkItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "张三",
                "TEXT", "My class is great.\n\nWe learn English together every day.", null,
                List.of(new Ports.AnswerRow(UUID.randomUUID(), 1, "A")),
                List.of(new Ports.ItemSpec(UUID.randomUUID(), 1, "ESSAY", "SECRET",
                        new BigDecimal("15"), "Write about your summer holiday.")),
                null, null, "zh-CN");
    }

    private AiGradingEngine engine(FakeLlmClient llm) {
        return engine(llm, EssayRubricWeights.DEFAULT);
    }

    private AiGradingEngine engine(FakeLlmClient llm, EssayRubricWeights weights) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new AiGradingEngine(new PromptBuilder(), new GradeOutputValidator(),
                (LlmClientProvider) teacherId -> llm, aiCalls, clock, weights);
    }

    private static LlmClient.LlmResponse response(String content, int tokensIn, int tokensOut, long latency) {
        return new LlmClient.LlmResponse("deepseek-flash", content, tokensIn, tokensOut, latency);
    }

    private static final String VALID_JSON = """
            {"criteria":[{"name":"内容要点","points":9,"comment":"正文写到了“My class is great”，并说明每天一起学英语，覆盖了题目要求的要点。"},
            {"name":"语言准确性","points":4,"comment":"使用了“We learn English together every day”，语法基本准确。"}],
            "total":13,"overall":"总评：文章表达了班级学习情况，结构清楚。","strength":"用“We learn English together every day”说明了日常学习。","suggestion":"建议补充一个具体的课堂活动例子。"}""";

    /** 模型把字段名写进正文本身：strength 以「优点：」开头、suggestion 以「建议：」开头。 */
    private static final String LABELED_JSON = """
            {"criteria":[{"name":"内容要点","points":9,"comment":"正文写到了“My class is great”，并说明每天一起学英语，覆盖了题目要求的要点。"},
            {"name":"语言准确性","points":4,"comment":"使用了“We learn English together every day”，语法基本准确。"}],
            "total":13,"overall":"总评：文章表达了班级学习情况，结构清楚。","strength":"优点：用“We learn English together every day”说明了日常学习。","suggestion":"建议：补充一个具体的课堂活动例子。"}""";

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
