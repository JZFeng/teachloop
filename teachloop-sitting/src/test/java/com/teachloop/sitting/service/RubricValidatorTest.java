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
    @DisplayName("缺分值：拒绝，不再静默平均分")
    void rejectsMissingPoints() {
        RubricValidator.Result result = RubricValidator.validate(
                "# 评分标准\n- 内容要点（10 分）：是否切题。\n- 语言准确性：语法与用词。", new BigDecimal("10"));

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).anyMatch(issue ->
                issue.code().equals("error.rubric.missingPoints") && issue.args().contains("语言准确性"));
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
}
