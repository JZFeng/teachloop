package com.teachloop.grading.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D-28：作文默认评分比例（内容 + 语法与拼写）的合成与校验。 */
class EssayRubricWeightsTest {

    @Test
    @DisplayName("默认 80/20：15 分拆成 12 + 3，rubric 两项带分值")
    void defaultSplit() {
        EssayRubricWeights weights = EssayRubricWeights.DEFAULT;

        assertThat(weights.contentPercent()).isEqualTo(80);
        assertThat(weights.languagePercent()).isEqualTo(20);
        assertThat(weights.contentPoints(new BigDecimal("15"))).isEqualByComparingTo("12");
        assertThat(weights.languagePoints(new BigDecimal("15"))).isEqualByComparingTo("3");

        String markdown = weights.defaultMarkdown(new BigDecimal("15"), false);
        assertThat(markdown)
                .contains("- 内容与任务完成（12 分）")
                .contains("- 语法与拼写（3 分）")
                .contains("是否完整回应题目要求");
    }

    @Test
    @DisplayName("小数满分不丢分：14 分 → 11.2 + 2.8，两项相加等于满分")
    void roundsWithoutLosingPoints() {
        EssayRubricWeights weights = EssayRubricWeights.DEFAULT;

        BigDecimal content = weights.contentPoints(new BigDecimal("14"));
        BigDecimal language = weights.languagePoints(new BigDecimal("14"));

        assertThat(content).isEqualByComparingTo("11.2");
        assertThat(language).isEqualByComparingTo("2.8");
        assertThat(content.add(language)).isEqualByComparingTo("14");
    }

    @Test
    @DisplayName("比例可配置：70% 内容 → 10.5 + 4.5")
    void configurablePercent() {
        EssayRubricWeights weights = new EssayRubricWeights(70);

        assertThat(weights.languagePercent()).isEqualTo(30);
        assertThat(weights.contentPoints(new BigDecimal("15"))).isEqualByComparingTo("10.5");
        assertThat(weights.languagePoints(new BigDecimal("15"))).isEqualByComparingTo("4.5");
        assertThat(weights.defaultMarkdown(new BigDecimal("15"), false))
                .contains("- 内容与任务完成（10.5 分）")
                .contains("- 语法与拼写（4.5 分）");
    }

    @Test
    @DisplayName("英文反馈时 rubric 用英文")
    void englishMarkdown() {
        assertThat(EssayRubricWeights.DEFAULT.defaultMarkdown(new BigDecimal("15"), true))
                .contains("# Rubric")
                .contains("- Content and task completion (12 points)")
                .contains("- Grammar and spelling (3 points)");
    }

    @Test
    @DisplayName("旧快照里的系统伪造 rubric 视为没有 rubric，按配置比例判")
    void treatsLegacyFakeRubricAsMissing() {
        String legacy = """
                # 评分标准
                - 内容要点：是否覆盖题目要求的全部要点。
                - 语言准确性：语法、用词与句式是否准确得体。
                """;

        assertThat(EssayRubricWeights.isLegacyDefault(legacy)).isTrue();
        assertThat(EssayRubricWeights.isLegacyDefault("# 评分标准\n- 内容要点（10 分）：老师自己写的")).isFalse();
        assertThat(EssayRubricWeights.isLegacyDefault(null)).isFalse();
    }

    @Test
    @DisplayName("非法比例在启动/构造时就报错，不静默按均分")
    void rejectsInvalidPercent() {
        assertThatThrownBy(() -> new EssayRubricWeights(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content-percent");
        assertThatThrownBy(() -> new EssayRubricWeights(100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
