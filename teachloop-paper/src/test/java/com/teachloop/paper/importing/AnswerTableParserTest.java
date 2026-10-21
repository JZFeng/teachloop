package com.teachloop.paper.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** T236：粘贴答案表要吃掉老师的朴素写法，同时不能把填空题答案读成字母。 */
class AnswerTableParserTest {

    private static Map<Integer, String> answers(AnswerTableParser.Result result) {
        return result.entries().stream()
                .collect(Collectors.toMap(AnswerTableParser.Entry::number, AnswerTableParser.Entry::answer,
                        (first, second) -> first, TreeMap::new));
    }

    @Test
    void parsesPlainPairsWithAndWithoutPunctuation() {
        AnswerTableParser.Result result = AnswerTableParser.parse("1.A\n2B\n3 A\n");

        assertThat(answers(result)).containsExactly(Map.entry(1, "A"), Map.entry(2, "B"), Map.entry(3, "A"));
    }

    @Test
    void acceptsSpaceSeparatedRange() {
        AnswerTableParser.Result result = AnswerTableParser.parse("1-5: A B B C C\n");

        assertThat(answers(result)).containsExactly(
                Map.entry(1, "A"), Map.entry(2, "B"), Map.entry(3, "B"), Map.entry(4, "C"), Map.entry(5, "C"));
    }

    @Test
    void stripsMarkdownDecorationBeforeParsing() {
        String text = """
                ## 参考答案
                ---
                * **A 篇 (21-23):** 21. **D** (共 3040 元) | 22. **B** | 23. **A**
                36. **G**
                """;

        AnswerTableParser.Result result = AnswerTableParser.parse(text);

        assertThat(answers(result)).containsExactly(
                Map.entry(21, "D"), Map.entry(22, "B"), Map.entry(23, "A"), Map.entry(36, "G"));
    }

    @Test
    void doesNotReadEnglishWordsAsLetters() {
        // 56 是填空题：整行内容就是答案，不会被 [A-Ga-g] 啃成 "c"
        AnswerTableParser.Result result = AnswerTableParser.parse("56. challenging\n57. anxiety\n", Set.of(56, 57));

        assertThat(answers(result)).containsExactly(
                Map.entry(56, "challenging"), Map.entry(57, "anxiety"));
    }

    @Test
    void splitsSeveralFreeTextAnswersOnOneLine() {
        AnswerTableParser.Result result =
                AnswerTableParser.parse("66. to walk  67. impressive  68. playing\n", Set.of(66, 67, 68));

        assertThat(answers(result)).containsExactly(
                Map.entry(66, "to walk"), Map.entry(67, "impressive"), Map.entry(68, "playing"));
    }

    @Test
    void cutsTrailingCommentAfterSpaceAndBracket() {
        AnswerTableParser.Result result =
                AnswerTableParser.parse("56. challenging (修饰名词 experience，用形容词形式)\n", Set.of(56));

        assertThat(answers(result)).containsExactly(Map.entry(56, "challenging"));
    }

    @Test
    void rejectsWordAnswerForChoiceQuestion() {
        // 不在填空题白名单里 → 仍然只认字母，写错就明确报错，绝不默默存成 "C"
        assertThatThrownBy(() -> AnswerTableParser.parse("56. challenging\n", Set.of()))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("无法识别为答案");
    }

    @Test
    void skipsHeadingsButStillParsesTheRest() {
        AnswerTableParser.Result result = AnswerTableParser.parse("""
                参考答案
                第一部分 听力（1-20）
                1-5 ABBCC
                """);

        assertThat(answers(result)).containsExactly(
                Map.entry(1, "A"), Map.entry(2, "B"), Map.entry(3, "B"), Map.entry(4, "C"), Map.entry(5, "C"));
    }

    @Test
    void rejectsEmptyText() {
        assertThatThrownBy(() -> AnswerTableParser.parse("  \n"))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("答案表是空的");
    }

    @Test
    void warnsAboutDuplicateNumber() {
        AnswerTableParser.Result result = AnswerTableParser.parse("1 A\n1 B\n");

        assertThat(result.entries()).extracting(AnswerTableParser.Entry::number).containsExactly(1);
        assertThat(result.entries().get(0).answer()).isEqualTo("A");
        assertThat(result.warnings()).anyMatch(w -> w.contains("重复"));
    }

    @Test
    void rejectsRangeWhoseCountDoesNotMatch() {
        assertThatThrownBy(() -> AnswerTableParser.parse("1-3 AB\n"))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("有 3 题");
    }

    @Test
    void reportsLineNumberWhenNothingParses() {
        // 一行都没解析出来时要吵，而且吵在原文那一行上（App 层 AnswerTableTest 依赖这条）
        assertThatThrownBy(() -> AnswerTableParser.parse("只有说明文字\n"))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("第 1 行")
                .hasMessageContaining("无法识别为答案");
    }

    @Test
    void keepsLineNumbersPointingAtTheOriginalText() {
        List<String> lines = List.of("参考答案", "---", "1 A");

        AnswerTableParser.Result result = AnswerTableParser.parse(String.join("\n", lines));

        assertThat(result.entries()).extracting(AnswerTableParser.Entry::line).containsExactly(3);
    }
}
