package com.teachloop.paper.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T186：题目与答案同文档的文本解析规则。 */
class PaperTextParserTest {

    private static PaperTextParser.Result parseMarkdown(String text) {
        DocumentTextExtractor.ExtractedDocument document =
                DocumentTextExtractor.extract("paper.md", text.getBytes(StandardCharsets.UTF_8));
        return PaperTextParser.parse(document.lines());
    }

    @Test
    void parsesThreeSingleChoiceQuestionsAndTheirAnswerBlock() {
        String text = """
                一、单项选择（每题 2 分）
                1. 中国的首都是哪座城市？
                A. 北京
                B. 上海
                C. 广州
                2. 2 + 2 等于多少？
                A. 3
                B. 4
                C. 5
                3. 水的化学式是什么？
                A. H2O
                B. CO2
                C. O2

                答案
                1. A
                2. C
                3. B
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.missingAnswers()).isEmpty();
        assertThat(result.items()).hasSize(3);
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::number)
                .containsExactly(1, 2, 3);
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "C", "B");
        PaperTextParser.ParsedItem first = result.items().get(0);
        assertThat(first.stem()).isEqualTo("中国的首都是哪座城市？");
        assertThat(first.options()).isEqualTo("A. 北京\nB. 上海\nC. 广州");
        assertThat(first.sourceLine()).isEqualTo(2);
    }

    @Test
    void pairsRangeAnswersAndInlineAnswer() {
        String text = """
                1. 法国的首都是哪里？ （答案：D）
                A. 柏林
                B. 马德里
                C. 罗马
                D. 巴黎
                36. 第 36 题
                A. 甲
                B. 乙
                37. 第 37 题
                A. 甲
                B. 乙
                38. 第 38 题
                A. 甲
                B. 乙
                39. 第 39 题
                A. 甲
                B. 乙
                40. 第 40 题
                A. 甲
                B. 乙
                参考答案
                36-40 ABCDA
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.missingAnswers()).isEmpty();
        assertThat(result.items()).hasSize(6);
        assertThat(result.items().get(0).stem()).isEqualTo("法国的首都是哪里？");
        assertThat(result.items().get(0).answerKey()).isEqualTo("D");
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::number)
                .containsExactly(1, 36, 37, 38, 39, 40);
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("D", "A", "B", "C", "D", "A");
    }

    @Test
    void acceptsSeveralAnswerPairsOnOneLine() {
        String text = """
                1. 第一题
                A. 甲
                2. 第二题
                A. 甲
                3. 第三题
                A. 甲
                答案：1 A 2 b 3 C
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.missingAnswers()).isEmpty();
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "B", "C");
    }

    @Test
    void reportsMissingAnswersWithoutDroppingQuestions() {
        String text = """
                1. 甲题
                A. 一
                B. 二
                2. 乙题
                A. 一
                B. 二
                3. 丙题
                A. 一
                B. 二
                答案
                1. A
                3. B
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).hasSize(3);
        assertThat(result.missingAnswers()).containsExactly(2);
        assertThat(result.items().get(1).answerKey()).isNull();
    }

    @Test
    void rejectsDuplicateQuestionNumberWithLineNumber() {
        String text = """
                1. 甲
                A. 一
                2. 乙
                A. 一
                1. 丙
                A. 一
                """;

        assertThatThrownBy(() -> parseMarkdown(text))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("第 5 行")
                .hasMessageContaining("题号 1 重复");
    }

    @Test
    void rejectsUnparsableAnswerLineWithLineNumber() {
        String text = """
                1. 甲题
                A. 一
                答案
                1. A
                3 这行有数字但解析不了
                """;

        assertThatThrownBy(() -> parseMarkdown(text))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("第 5 行")
                .hasMessageContaining("无法解析");
    }

    @Test
    void rejectsRangeWhoseAnswerCountDoesNotMatch() {
        String text = """
                1. 甲题
                A. 一
                2. 乙题
                A. 一
                3. 丙题
                A. 一
                答案
                1-3 AB
                """;

        assertThatThrownBy(() -> parseMarkdown(text))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("第 8 行")
                .hasMessageContaining("3 个答案");
    }

    @Test
    void warnsAboutAnswerForUnknownQuestion() {
        String text = """
                1. 甲题
                A. 一
                答案
                1. A
                9. C
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).hasSize(1);
        assertThat(result.warnings()).anyMatch(w -> w.contains("答案题号 9"));
    }

    @Test
    void acceptsMultiLetterAnswerKey() {
        String text = """
                1. 多选题
                A. 一
                B. 二
                C. 三
                答案
                1. AB
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).answerKey()).isEqualTo("AB");
    }

    @Test
    void acceptsEnglishAnswerMarkerAndAsciiInlineAnswer() {
        String text = """
                1. What is the capital of France? (答案: A)
                A. Paris
                B. Rome
                2. What is 2 + 2?
                A. 3
                B. 4
                Answers
                2 B
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.missingAnswers()).isEmpty();
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "B");
        assertThat(result.items().get(0).stem()).isEqualTo("What is the capital of France?");
    }

    @Test
    void rejectsDocumentWithoutAnyQuestion() {
        assertThatThrownBy(() -> parseMarkdown("只是一段说明文字\n没有题号\n"))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("没有识别到任何题号");
    }
}
