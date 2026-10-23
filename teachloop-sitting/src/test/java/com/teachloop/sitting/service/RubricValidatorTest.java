package com.teachloop.sitting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RubricValidatorTest {

    @Test
    @DisplayName("课程 Rubric.md：3 项 2/4/4，Yes/No 规则不算评分项")
    void acceptsCourseRubric() throws Exception {
        String rubric = Files.readString(Path.of("..", "docs", "rubrics", "Rubric.md"));

        RubricValidator.Result result = RubricValidator.validate(rubric, new BigDecimal("10"));

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.criteria()).extracting(RubricValidator.Criterion::name)
                .containsExactly("Required Length", "Clear Position", "Explanation and Supporting Details");
        assertThat(result.criteria()).extracting(RubricValidator.Criterion::maxPoints)
                .containsExactly(new BigDecimal("2"), new BigDecimal("4"), new BigDecimal("4"));
        assertThat(result.total()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("没有分值的条目：当非评分项忽略，不再阻断（T340）")
    void ignoresUnpointedLines() {
        RubricValidator.Result result = RubricValidator.validate(
                "# 评分标准\n- 内容要点（10 分）：是否切题。\n- 语言准确性：语法与用词。", new BigDecimal("10"));

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.criteria()).extracting(RubricValidator.Criterion::name)
                .containsExactly("内容要点");
        assertThat(result.ignored()).containsExactly("语言准确性");
    }

    @Test
    @DisplayName("分值之和不等于作业总分：拒绝并给出两边数字")
    void rejectsSumMismatch() {
        RubricValidator.Result result = RubricValidator.validate(
                "# 评分标准\n- 内容要点（10 分）：是否切题。\n- 语言准确性（5 分）：语法与用词。",
                new BigDecimal("10"));

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).anyMatch(issue ->
                issue.code().equals("error.rubric.sumMismatch")
                        && issue.args().contains("15") && issue.args().contains("10"));
    }

    @Test
    @DisplayName("重复评分项：拒绝")
    void rejectsDuplicateNames() {
        RubricValidator.Result result = RubricValidator.validate(
                "# 评分标准\n- 内容要点（5 分）：A。\n- 内容要点（5 分）：B。", new BigDecimal("10"));

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).anyMatch(issue ->
                issue.code().equals("error.rubric.duplicate") && issue.args().contains("内容要点"));
    }

    @Test
    @DisplayName("问句式评分项「是否…」不能被当成 Yes/No 判定行丢掉")
    void keepsQuestionFormCriteria() {
        RubricValidator.Result result = RubricValidator.validate("""
                # 评分标准
                1. 是否提交了与阅读相关的英文教案？（2 分）
                2. 是否包含模板全部要素？（6 分）
                3. 是否至少设计了一个精读活动？（3 分）
                4. 是否至少设计了一个泛读活动？（3 分）
                """, new BigDecimal("14"));

        assertThat(result.valid()).withFailMessage(() -> result.issues().toString()).isTrue();
        assertThat(result.criteria()).hasSize(4);
        assertThat(result.total()).isEqualByComparingTo("14");
    }

    @Test
    @DisplayName("整句问句当评分项（超过旧 80 字符上限）不能丢：2+6+3+3 必须等于 14")
    void keepsLongQuestionCriteria() {
        RubricValidator.Result result = RubricValidator.validate("""
                # Did the learner submit a lesson plan related to a reading in English? （2 points）

                # Did the learner include all the components as provided in the lesson plan template in English?  （6 points）

                # Did the learner use at least one intensive reading activity in English?   (3 points)

                # Did the learner use at least one extensive reading activity in English?   (3 points)
                """, new BigDecimal("14"));

        assertThat(result.valid()).withFailMessage(() -> result.issues().toString()).isTrue();
        assertThat(result.criteria()).hasSize(4);
        assertThat(result.total()).isEqualByComparingTo("14");
    }
}
