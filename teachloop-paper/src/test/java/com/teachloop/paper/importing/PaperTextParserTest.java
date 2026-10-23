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

    /** 老师会把「答案」写成「标准答案」「正确答案」或加方括号，四种都要认。 */
    @Test
    void acceptsEveryAnswerKeywordSpelling() {
        String template = """
                1. 甲题
                A. 一
                2. 乙题
                A. 一
                %s
                1 A
                2 B
                """;
        for (String keyword : List.of("答案", "参考答案", "标准答案", "正确答案", "【答案】", "【标准答案】")) {
            PaperTextParser.Result result = parseMarkdown(template.formatted(keyword));

            assertThat(result.missingAnswers()).as("关键字 %s", keyword).isEmpty();
            assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                    .as("关键字 %s", keyword).containsExactly("A", "B");
        }
    }

    /** 漏填一道不该让整卷导入失败，但要留下可定位的告警。 */
    @Test
    void skipsBareNumberEntriesInsteadOfFailing() {
        String text = """
                1. 甲题
                A. 一
                2. 乙题
                A. 一
                3. 丙题
                A. 一
                答案
                1. A
                2. B
                3.
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "B", null);
        assertThat(result.missingAnswers()).containsExactly(3);
        assertThat(result.warnings()).anyMatch(w -> w.contains("题号 3 没有答案"));
    }

    /** 答题卡形态的纯字母串：题号连续时按顺序配。 */
    @Test
    void pairsPlainLetterSequenceWhenNumbersAreContiguous() {
        String text = """
                1. 甲题
                A. 一
                2. 乙题
                A. 一
                3. 丙题
                A. 一
                答案
                A B C
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.missingAnswers()).isEmpty();
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "B", "C");
    }

    /** 题号不连续时拒绝配对：错位给分会比直接失败更糟。 */
    @Test
    void refusesPlainLetterSequenceWhenNumbersHaveGaps() {
        String text = """
                1. 甲题
                A. 一
                2. 乙题
                A. 一
                7. 丙题
                A. 一
                答案
                A B C
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly(null, null, null);
        assertThat(result.missingAnswers()).containsExactly(1, 2, 7);
        assertThat(result.warnings()).anyMatch(w -> w.contains("无法对应到连续题号"));
    }

    /** T236：答案区里的小标题、带解析的行都不该让整卷导入失败。 */
    @Test
    void survivesHeadingsAndCommentaryInsideAnswerZone() {
        String text = """
                1. 甲题
                A. 一
                2. 乙题
                A. 一
                3. 丙题
                A. 一
                4. 丁题
                A. 一
                5. 戊题
                A. 一
                参考答案
                ### 第一部分 听力（1-20）
                **第一节：**
                1-5: A B B C C
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.missingAnswers()).isEmpty();
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "B", "B", "C", "C");
    }

    /** 卷末附录标题常常带前缀，如「试卷参考答案与解析 (卷末附录)」。 */
    @Test
    void findsAnswerZoneByHeadingThatMentionsReferenceAnswers() {
        String text = """
                1. 甲题
                A. 一
                2. 乙题
                A. 一
                3. 丙题
                A. 一
                # 试卷参考答案与解析 (卷末附录)
                21-23: D B A
                1-3: A B C
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.missingAnswers()).isEmpty();
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "B", "C");
    }

    /** T236：填空题的答案就是文本，不能被读成字母。 */
    @Test
    void readsFillBlankAnswersAsText() {
        String text = """
                1. 选择题
                A. 一
                2. 选择题
                A. 一
                56. 语法填空 ______
                57. 语法填空 ______
                答案
                1 A
                2 B
                56. challenging (修饰名词 experience)
                57. to walk  58. impressive
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::number)
                .containsExactly(1, 2, 56, 57);
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "B", "challenging", "to walk");
        assertThat(result.missingAnswers()).isEmpty();
    }

    /** 阅读材料的 A/B/C/D 标签只用于切分材料，不能出现在题干里（2026-10-21 修）。 */
    @Test
    void doesNotPutPassageLabelsIntoStems() {
        String text = """
                第二部分 阅读理解
                A
                Passage A text first line.
                Passage A text second line.
                21. Question A one?
                A. aa
                B. bb
                C. cc
                22. Question A two?
                A. aa
                B. bb
                C. cc
                B
                Passage B text.
                23. Question B one?
                A. aa
                B. bb
                C. cc
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).hasSize(3);
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::stem).containsExactly(
                "Passage A text first line.\nPassage A text second line.\n\nQuestion A one?",
                "Passage A text first line.\nPassage A text second line.\n\nQuestion A two?",
                "Passage B text.\n\nQuestion B one?");
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::options)
                .containsOnly("A. aa\nB. bb\nC. cc");
    }

    /** 卷面自带题号时，标记要连同括号题号一起吸收，别留下「___(9)___ (9)」双写（2026-10-21 修）。 */
    @Test
    void absorbsTheQuestionNumberAlreadyWrittenNextToTheBlank() {
        assertThat(PaperTextParser.labelBlank("but seeing ______ (9) faces", 0, 9))
                .isEqualTo("but seeing ___(9)___ faces");
        assertThat(PaperTextParser.labelBlank("（7）______ Keep a notebook", 0, 7))
                .isEqualTo("___(7)___ Keep a notebook");
        assertThat(PaperTextParser.labelBlank("only ______ and ______ (7)", 1, 7))
                .isEqualTo("only ______ and ___(7)___");
    }

    /** 括号里不是题号时不能吃掉：______ (2019) 是正文。 */
    @Test
    void keepsParenthesizedTextThatIsNotTheBlankNumber() {
        assertThat(PaperTextParser.labelBlank("in ______ (2019) the school was founded", 0, 9))
                .isEqualTo("in ___(9)___ (2019) the school was founded");
    }

    /**
     * 下划线绕到题号两边时还是一个空（2026-10-22 用户实测）：{@code ______(7)___} 曾按两段下划线
     * 数成两个空，七选五 5 个空变成 6 道题，题号与答案区整体错位。
     */
    @Test
    void countsOneBlankWhenUnderscoresSurroundTheQuestionNumber() {
        String text = """
                阅读下面短文，从短文后的选项中选出最佳选项。
                Keeping up with teachers is a good way. ______(7)___ Keep a notebook to record mistakes.

                A. Don't let your friendships die.
                B. Your friends are good for your study.
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).number()).isEqualTo(7);
        assertThat(result.items().get(0).stem())
                .isEqualTo("Keeping up with teachers is a good way. ___(7)___ Keep a notebook to record mistakes.");
    }

    /** 「(7) 带下划线」的另一种写法 {@code __(7)__}：只有两段下划线，也认成一个空并取题号 7。 */
    @Test
    void acceptsUnderlinedQuestionNumberWithTwoUnderscores() {
        String text = """
                阅读下面短文，从短文后的选项中选出最佳选项。
                Keeping up with teachers is a good way. __(7)__ Keep a notebook to record mistakes.
                The school life may be hard, but you're not alone. __(8)__

                A. Don't let your friendships die.
                B. Your friends are good for your study.
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::number).containsExactly(7, 8);
        assertThat(result.items().get(0).stem()).contains("___(7)___");
        assertThat(result.items().get(1).stem()).contains("___(8)___");
    }

    /** HTML 下划线标签 {@code <u>(7)</u>} 是排版，导入时要剥掉标签、照常认成一个空。 */
    @Test
    void acceptsUnderlineTagsAroundTheQuestionNumber() {
        String text = """
                阅读下面短文，从短文后的选项中选出最佳选项。
                Keeping up with teachers is a good way. ______<u>(7)</u>___ Keep a notebook to record mistakes.

                A. Don't let your friendships die.
                B. Your friends are good for your study.
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).number()).isEqualTo(7);
        assertThat(result.items().get(0).stem()).contains("___(7)___").doesNotContain("<u>").doesNotContain("</u>");
    }

    /**
     * 用户原样的七选五片段：7、8 两个空写了题号，后三个空没写；应得到 7–11 共 5 题，
     * 而不是 6 题（第一个空被数成两段）。
     */
    @Test
    void numbersTheSevenOutOfSevenFragmentByItsWrittenNumbers() {
        String text = """
                How can English be learned well? In my view, keeping up with teachers is a good way.
                But remember to work in a way that suits you. ______(7) Keep a notebook to make a record.

                The school life may be hard, but you're not alone. ___(8)

                Going to bed no later than 11:30 pm is important. ______ You needn't follow a special diet.

                ______ Having a lot of schoolwork doesn't mean having to quit other activities.

                Besides taking the College Entrance Examination, you have other choices for college. ______

                A. Don't let your friendships die.
                B. Your friends are good for your study.
                C. If you took notes in class, re-read them.
                D. The person who plays well studies well.
                E. It puts you in the right state for the next day.
                F. Some students even apply for studying abroad.
                G. It's important to learn from past mistakes as well.
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::number)
                .containsExactly(7, 8, 9, 10, 11);
        for (PaperTextParser.ParsedItem item : result.items()) {
            assertThat(item.stem()).contains("___(" + item.number() + ")___");
        }
        // 同一段材料在每道题里各存一份：没轮到的空要收成统一写法，答题页才能认出是同一篇材料
        assertThat(result.items().get(0).stem()).contains("______ (8)");
        assertThat(result.items().get(1).stem()).contains("______ (7)");
    }

    /** 括号里是四位年份时不能和空并成一个（否则两个空会少算一个）。 */
    @Test
    void doesNotMergeFourDigitParenthesizedTextIntoTheBlank() {
        String text = """
                阅读下面短文，在空白处填入适当内容。
                The school ______ (2019) was founded, and the library ______ was built later.

                A. 甲
                B. 乙
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::number).containsExactly(1, 2);
        assertThat(result.items().get(0).stem()).contains("(2019)");
    }

    /**
     * 七选五「材料里 5 个空、但只有 2 道题」：填空题顺推题号不能把后面真正的 9/10/11 吃掉。
     *
     * <p>2026-10-22 真实卷（AI立即.md）：七选五的 5 个空按顺推占用了 7–11，导致完形填空的
     * 9/10 撞号、语法填空的 11 不存在，答案区「11. challenging」被当成选择题答案而报
     * 「无法解析的答案行」。空只在没有卷面题号占用时才能成题。
     */
    @Test
    void blankGroupStopsAtNumbersAlreadyWrittenOnThePaper() {
        String text = """
                第二部分 阅读理解
                5. 阅读题一？
                A. a
                B. b
                6. 阅读题二？
                A. a
                B. b

                第二节 七选五
                阅读下面短文，从短文后的选项中选出最佳选项。
                第一处 ______ 第二处 ______ 第三处 ______ 第四处 ______ 第五处 ______

                A. 选项甲
                B. 选项乙
                C. 选项丙
                D. 选项丁
                E. 选项戊
                F. 选项己
                G. 选项庚

                第三部分 英语知识运用
                第一节 完形填空
                课文里有两个空 ______ ______
                9. A. old  B. new
                10. A. change  B. keep

                第二节 语法填空
                课文里两个空 ______ (11) (challenge) 与 ______ (12) (anxious)

                第三节 单句语法填空
                13. 单句 ______ (walk) 适合这里。
                14. 单句 ______ (impress) 适合这里。

                答案
                5. A
                6. B
                7. G
                8. A
                9. A
                10. B
                11. challenging
                12. anxiety
                13. to walk
                14. impressive
                """;

        PaperTextParser.Result result = parseMarkdown(text);

        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::number)
                .containsExactly(5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "B", "G", "A", "A", "B", "challenging", "anxiety",
                        "to walk", "impressive");
        assertThat(result.missingAnswers()).isEmpty();
    }

    /** 选择题写非字母答案仍然报错，不静默存成 "C"。 */
    @Test
    void stillRejectsWordAnswerForChoiceQuestion() {
        String text = """
                1. 选择题
                A. 一
                答案
                1. challenging
                """;

        assertThatThrownBy(() -> parseMarkdown(text))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("无法解析的答案行");
    }
}
