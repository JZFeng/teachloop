package com.teachloop.shared.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 内部空标记 {@code ___(N)___} 的展示口径。
 *
 * <p>2026-10-21 实测：导入器把卷面自带的 {@code ______ (9)} 标成 {@code ___(9)___ (9)}，
 * 学生反馈页原样展示，就成了「括号 9 + 下划线 (9)」双写。这里锁死两个口径的输出。
 */
class BlankTextTest {

    @Test
    void statementKeepsTheBlankNumberExactlyOnce() {
        assertThat(BlankText.forText("but seeing ___(9)___ (9) faces")).isEqualTo("but seeing ______ (9) faces");
    }

    @Test
    void statementRestoresBareMarkers() {
        assertThat(BlankText.forText("I prefer ___(66)___ (walk) to school."))
                .isEqualTo("I prefer ______ (66) (walk) to school.");
    }

    @Test
    void statementHandlesNumberWrittenBeforeTheBlank() {
        assertThat(BlankText.forText("（7）___(7)___ Keep a notebook"))
                .isEqualTo("______ (7) Keep a notebook");
    }

    @Test
    void statementLeavesPlainBlanksAndCueWordsAlone() {
        assertThat(BlankText.forText("It's as important to live well as it is to study ______ (10) well."))
                .isEqualTo("It's as important to live well as it is to study ______ (10) well.");
        assertThat(BlankText.forText("She is good at ______ (sing)."))
                .isEqualTo("She is good at ______ (sing).");
    }

    @Test
    void statementDoesNotSwallowNumberThatIsNotTheBlankLabel() {
        // 括号里是年份，不是题号：不能拿它当重复标签吃掉。
        assertThat(BlankText.forText("in ___(9)___ (2019) the school was founded"))
                .isEqualTo("in ______ (9) (2019) the school was founded");
    }

    @Test
    void bothRulesAreIdempotent() {
        String once = BlankText.forText("but seeing ___(9)___ (9) faces");
        assertThat(BlankText.forText(once)).isEqualTo(once);

        String groupedOnce = BlankText.forGrouped("but seeing ___(9)___ (9) faces");
        assertThat(BlankText.forGrouped(groupedOnce)).isEqualTo(groupedOnce);
    }

    @Test
    void groupedMaterialDropsTheNumberBecauseTheInputWidgetShowsIt() {
        assertThat(BlankText.forGrouped("but seeing ___(9)___ (9) faces")).isEqualTo("but seeing ______ faces");
        assertThat(BlankText.forGrouped("（7）___(7)___ Keep a notebook")).isEqualTo("______ Keep a notebook");
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(BlankText.forText(null)).isNull();
        assertThat(BlankText.forText("")).isEmpty();
        assertThat(BlankText.forGrouped(null)).isNull();
    }
}
