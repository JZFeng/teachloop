package com.teachloop.grading.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teachloop.shared.port.Ports;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T080：prompt 只含主观信息；两层顺序固定；zh-CN / en 各断言一次。 */
class PromptBuilderTest {

    private static final UUID STUDENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String STUDENT_NAME = "张三";
    private static final String ANSWER_KEY = "SECRET-ANSWER-KEY";
    private static final String ESSAY = "My class is great.\n\nWe learn English together every day.";

    private final PromptBuilder builder = new PromptBuilder();

    @Test
    @DisplayName("zh-CN：system = 角色 + rubric + 契约，user = 客观指标 + 正文")
    void buildsChinesePrompt() {
        SubjectiveGradeRequest request = SubjectiveGradeRequest.from(workItem("zh-CN"));

        assertThat(request.criteria())
                .extracting(SubjectiveGradeRequest.Criterion::name)
                .containsExactly("内容要点", "语言准确性");
        assertThat(request.criteria())
                .extracting(SubjectiveGradeRequest.Criterion::maxPoints)
                .containsExactly(new BigDecimal("10"), new BigDecimal("5"));
        assertThat(request.subjectiveMax()).isEqualByComparingTo("15");
        assertThat(request.studentText()).isEqualTo(ESSAY);

        PromptBuilder.Prompt prompt = builder.build(request);
        assertThat(prompt.promptVersion()).isEqualTo("subjective-v3");
        assertThat(prompt.system())
                .contains("TESOL")
                .contains("Write about your class.")
                .contains("## 评分标准")
                .contains("内容要点")
                .contains("语言准确性")
                .contains("满分 10 分")
                .contains("满分 5 分")
                .contains("## 评分政策")
                .contains("## 输出契约")
                .contains("\"criteria\"")
                .contains("\"overall\"")
                .contains("不超过主观题满分 15 分");
        assertThat(prompt.user())
                .startsWith("## 客观指标")
                .contains("词数 10")
                .contains("段落数 2")
                .contains("## 本次反馈语言")
                .contains("## 学生正文")
                .contains(ESSAY);
    }

    @Test
    @DisplayName("en：语言由 feedbackLanguage 决定，契约与指标都是英文")
    void buildsEnglishPrompt() {
        SubjectiveGradeRequest request = SubjectiveGradeRequest.from(workItem("en"));

        PromptBuilder.Prompt prompt = builder.build(request);
        assertThat(prompt.system())
                .contains("TESOL instructor")
                .contains("Write about your class.")
                .contains("## Rubric")
                .contains("## Grading policy")
                .contains("## Output contract")
                .contains("points maximum")
                .contains("\"overall\"")
                .doesNotContain("你是一名");
        assertThat(prompt.user())
                .contains("## Objective metrics")
                .contains("Word count 10")
                .contains("paragraph count 2")
                .contains("## Feedback language")
                .contains("## Student response");
    }

    @Test
    @DisplayName("prompt 不含标准答案、客观得分、学生身份")
    void promptHasNoSensitiveData() {
        SubjectiveGradeRequest request = SubjectiveGradeRequest.from(workItem("zh-CN"));
        PromptBuilder.Prompt prompt = builder.build(request);

        for (String text : List.of(prompt.system(), prompt.user())) {
            assertThat(text)
                    .doesNotContain(ANSWER_KEY)
                    .doesNotContain("标准答案")
                    .doesNotContain("客观得分")
                    .doesNotContain("objectiveTotal")
                    .doesNotContain("answerKey")
                    .doesNotContain(STUDENT_ID.toString())
                    .doesNotContain(STUDENT_NAME);
        }
    }

    @Test
    @DisplayName("SubjectiveGradeRequest 类型上不存在答案/客观分/学生身份字段")
    void requestTypeHasNoSensitiveFields() {
        List<String> fields = Arrays.stream(SubjectiveGradeRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(fields)
                .doesNotContain("answerKey", "answers", "objectiveTotal", "objectivePoints",
                        "studentId", "studentName");
    }

    @Test
    @DisplayName("rubric 未写分值时按主观满分均分，作为结构化评分项进端口前的过渡")
    void splitsPointsWhenRubricOmitsThem() {
        Ports.WorkItem item = new Ports.WorkItem(UUID.randomUUID(), UUID.randomUUID(), STUDENT_ID,
                STUDENT_NAME, "TEXT", ESSAY, null, List.of(),
                List.of(new Ports.ItemSpec(UUID.randomUUID(), 1, "ESSAY", null, new BigDecimal("15"),
                        "Write about your class.")),
                "# 评分标准\n- 内容要点：是否覆盖题目要求的全部要点。\n- 语言准确性：语法与用词。",
                null, "zh-CN");

        SubjectiveGradeRequest request = SubjectiveGradeRequest.from(item);
        assertThat(request.criteria()).hasSize(2);
        assertThat(request.criteria().get(0).maxPoints()).isEqualByComparingTo("7.5");
        assertThat(request.criteria().get(1).maxPoints()).isEqualByComparingTo("7.5");
        assertThat(request.criteria().stream()
                .map(SubjectiveGradeRequest.Criterion::maxPoints)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("Rubric.md：标题是评分项，Yes/No 是判定规则，不混进 criteria")
    void parsesHeadingRubricAndSkipsRuleBullets() {
        String rubric = """
                # Overall Assignment Rubric

                ## Scoring Rubric

                ### 1. Required Length — 2 points
                Was it between 1–4 paragraphs?
                - Yes: 2 points
                - No: 0 points

                ### 2. Clear Position — 4 points
                - Yes: 4 points
                - No: 0 points

                ### 3. Explanation and Supporting Details — 4 points
                - Yes: 4 points
                - No: 0 points

                ## Maximum Score
                10 points
                """;
        Ports.WorkItem item = new Ports.WorkItem(UUID.randomUUID(), UUID.randomUUID(), STUDENT_ID,
                STUDENT_NAME, "TEXT", ESSAY, null, List.of(),
                List.of(new Ports.ItemSpec(UUID.randomUUID(), 1, "ESSAY", null, new BigDecimal("10"),
                        "Write about your class.")),
                rubric, null, "en");

        SubjectiveGradeRequest request = SubjectiveGradeRequest.from(item);

        assertThat(request.criteria()).extracting(SubjectiveGradeRequest.Criterion::name)
                .containsExactly("Required Length", "Clear Position", "Explanation and Supporting Details");
        assertThat(request.criteria()).extracting(SubjectiveGradeRequest.Criterion::maxPoints)
                .containsExactly(new BigDecimal("2"), new BigDecimal("4"), new BigDecimal("4"));
        assertThat(request.binaryRubric()).isTrue();
        assertThat(request.lengthMin()).isEqualTo(1);
        assertThat(request.lengthMax()).isEqualTo(4);
    }

    @Test
    @DisplayName("锚点：只有教师确认的参考范文非空时才进入 system")
    void includesAnchorsOnlyWhenPresent() {
        String rubric = "# 评分标准\n- 内容要点（10 分）：是否切题。\n- 语言准确性（5 分）：语法与用词。";
        Ports.WorkItem without = new Ports.WorkItem(UUID.randomUUID(), UUID.randomUUID(), STUDENT_ID,
                STUDENT_NAME, "TEXT", ESSAY, null, List.of(),
                List.of(new Ports.ItemSpec(UUID.randomUUID(), 1, "ESSAY", null, new BigDecimal("15"),
                        "Write about your class.")),
                rubric, null, "zh-CN", null, "");
        Ports.WorkItem with = new Ports.WorkItem(UUID.randomUUID(), UUID.randomUUID(), STUDENT_ID,
                STUDENT_NAME, "TEXT", ESSAY, null, List.of(),
                List.of(new Ports.ItemSpec(UUID.randomUUID(), 1, "ESSAY", null, new BigDecimal("15"),
                        "Write about your class.")),
                rubric, null, "zh-CN", null, "示例：3 段，立场明确，4/4。片段：We learn English together.");

        assertThat(builder.build(SubjectiveGradeRequest.from(without)).system())
                .doesNotContain("参考范文");
        assertThat(builder.build(SubjectiveGradeRequest.from(with)).system())
                .contains("参考范文（教师确认、已匿名")
                .contains("We learn English together");
    }

    @Test
    @DisplayName("没有主观题时拒绝构造 prompt")
    void rejectsEmptyItems() {
        SubjectiveGradeRequest request = new SubjectiveGradeRequest(List.of(), ESSAY, 10, 2, null,
                List.of(new SubjectiveGradeRequest.Criterion("内容要点", new BigDecimal("10"))),
                "# 评分标准", "zh-CN", "Write about your class.", false, 1, 4, "policy_default", false, "");
        assertThatThrownBy(() -> builder.build(request)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Ports.WorkItem workItem(String language) {
        String rubric = language.startsWith("en")
                ? "# Scoring Rubric\n### 1. Content — 10 points\n### 2. Language accuracy — 5 points\n"
                : "# 评分标准\n- 内容要点（10 分）：是否覆盖题目要求的全部要点。\n"
                  + "- 语言准确性（5 分）：语法、用词与句式是否准确得体。\n";
        return new Ports.WorkItem(UUID.randomUUID(), UUID.randomUUID(), STUDENT_ID, STUDENT_NAME, "TEXT",
                ESSAY, null,
                List.of(new Ports.AnswerRow(UUID.randomUUID(), 1, "A")),
                List.of(new Ports.ItemSpec(UUID.randomUUID(), 1, "SINGLE_CHOICE", ANSWER_KEY,
                                new BigDecimal("5"), "Multiple choice stem"),
                        new Ports.ItemSpec(UUID.randomUUID(), 2, "ESSAY", ANSWER_KEY,
                                new BigDecimal("15"), "Write about your class.")),
                rubric, null, language);
    }
}
