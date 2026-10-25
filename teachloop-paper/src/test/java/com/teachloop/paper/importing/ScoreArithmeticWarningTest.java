package com.teachloop.paper.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T454：标题行「题数 × 每题分值 = 满分」的自检。
 *
 * <p>2026-10-25 用户要求：能解析整行括号说明（{@code （共 5 小题；每小题 1.5 分，满分 7.5 分）}），
 * 用 {@code 5 × 1.5 == 7.5} 做验证；对不上只出提醒，不拦老师导入。同时把分值写法的容忍度放宽到
 * 斜杠式（{@code 1.5/题}、{@code 1.5 / quiz /question}）与英文式（{@code each （question）1.5}）。</p>
 */
class ScoreArithmeticWarningTest {

    @Test
    @DisplayName("原文写法：5 小题 × 1.5 分 = 7.5 分，自洽，无提醒")
    void acceptsTheOriginalHeader() {
        PaperTextParser.Result result = parse("第一节（共 5 小题；每小题 1.5 分，满分 7.5 分）");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.items()).hasSize(5).allSatisfy(item ->
                assertThat(item.points()).isEqualByComparingTo("1.5"));
    }

    @Test
    @DisplayName("斜杠式与英文式都能读出 5 × 1.5 = 7.5，且不误报")
    void acceptsTolerantSpellings() {
        List<String> headers = List.of(
                "第一节（total 5 questions， each （question）1.5， total score： 7.5）",
                "第一节（total 5 questions， each （question）1.5 points， total score： 7.5 points）",
                "第一节（共5题，1.5/题，满分7.5分）",
                "第一节（total 5 quizs， 1.5 / quiz /question ， total： 7.5）");

        for (String header : headers) {
            PaperTextParser.Result result = parse(header);

            assertThat(result.warnings()).as(header).isEmpty();
            assertThat(result.items()).as(header).hasSize(5);
            assertThat(result.items()).as(header).allSatisfy(item ->
                    assertThat(item.points()).isEqualByComparingTo("1.5"));
        }
    }

    @Test
    @DisplayName("满分写错只提醒、不阻断：仍导入 5 题，每题 1.5 分")
    void warnsButKeepsImportingWhenTheTotalDoesNotAddUp() {
        PaperTextParser.Result result = parse("第一节（共 5 小题；每小题 1.5 分，满分 8 分）");

        assertThat(result.items()).hasSize(5).allSatisfy(item ->
                assertThat(item.points()).isEqualByComparingTo("1.5"));
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("第 2 行")
                .contains("5 题 × 1.5 分 = 7.5 分")
                .contains("满分 8 分"));
    }

    @Test
    @DisplayName("三个数字缺一个就不猜：只有满分时不出提醒，也不编分值")
    void staysSilentWhenOneOfTheThreeNumbersIsMissing() {
        PaperTextParser.Result result = parse("第一节（满分 7.5 分）");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.items()).hasSize(5).allSatisfy(item ->
                assertThat(item.points()).isNull());
    }

    /** 一份 5 道题的最小卷子；小节标题由用例给定。 */
    private static PaperTextParser.Result parse(String subHeader) {
        StringBuilder raw = new StringBuilder("第一部分 听力\n").append(subHeader).append('\n');
        for (int number = 1; number <= 5; number++) {
            raw.append(number).append(". Question ").append(number).append("?\n")
                    .append("A. a\nB. b\nC. c\n");
        }
        return PaperTextParser.parse(DocumentTextExtractor.extract("score.md",
                raw.toString().getBytes(StandardCharsets.UTF_8)).lines());
    }
}
