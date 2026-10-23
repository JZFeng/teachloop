package com.teachloop.paper.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T231：拿一份真实的高一英语月考试卷（Markdown）做回归。
 *
 * <p>这份卷子是「排版什么样就长什么样」的真实产物：{@code ## 第一部分}、
 * {@code **A**}、Markdown 表格、完形填空一行四个选项、七选五有 5 个空但选项
 * A–G 列在文末、语法填空 56–65 在正文里根本没有题号、写作部分还带
 * {@code 1. 表达理解；}这种编号要求。旧解析器把它当成第 1 题重复直接整卷失败。</p>
 *
 * <p>断言的是「教师看到什么就导入什么」：75 道客观题 + 2 道写作题（76 / 77），
 * 4 个部分、总分 150 分（客观 120 + 写作 30，D-23）。</p>
 */
class RealExamImportTest {

    private static PaperTextParser.Result parseFixture() throws IOException {
        byte[] bytes;
        try (InputStream in = RealExamImportTest.class.getResourceAsStream("/exam/english-grade10-2026.md")) {
            assertThat(in).as("找不到 /exam/english-grade10-2026.md").isNotNull();
            bytes = in.readAllBytes();
        }
        DocumentTextExtractor.ExtractedDocument document =
                DocumentTextExtractor.extract("english-grade10-2026.md", bytes);
        return PaperTextParser.parse(document.lines());
    }

    @Test
    void keepsTheFourPartsOfThePaper() throws IOException {
        PaperTextParser.Result result = parseFixture();

        assertThat(result.sections()).extracting(PaperTextParser.ParsedSection::title)
                .containsExactly("第一部分 听力", "第二部分 阅读理解", "第三部分 英语知识运用", "第四部分 写作");
        assertThat(result.sections()).extracting(section -> section.items().size())
                .containsExactly(20, 20, 35, 2);
    }

    @Test
    void numbersEveryQuestionOneToOneSeventyFive() throws IOException {
        PaperTextParser.Result result = parseFixture();

        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::number)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 77).boxed().toList());
        assertThat(result.items().subList(75, 77)).allMatch(item -> item.questionType() != null);
    }

    @Test
    void carriesPerItemPointsFromEachSubSection() throws IOException {
        PaperTextParser.Result result = parseFixture();

        BigDecimal total = result.items().stream()
                .map(PaperTextParser.ParsedItem::points)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("150");
        assertThat(pointsOf(result, 76)).isEqualByComparingTo("15");
        assertThat(pointsOf(result, 77)).isEqualByComparingTo("15");
        assertThat(pointsOf(result, 1)).isEqualByComparingTo("1.5");
        assertThat(pointsOf(result, 21)).isEqualByComparingTo("2.5");
        assertThat(pointsOf(result, 41)).isEqualByComparingTo("1");
        assertThat(pointsOf(result, 56)).isEqualByComparingTo("1.5");
        assertThat(pointsOf(result, 66)).isEqualByComparingTo("1");
    }

    @Test
    void attachesReadingPassageAndKeepsTheMarkdownTable() throws IOException {
        PaperTextParser.Result result = parseFixture();
        PaperTextParser.ParsedItem question21 = item(result, 21);

        assertThat(question21.stem())
                .contains("Welcome to join our Summer Youth Language Program")
                .contains("Dates | Costs")
                .contains("10-week program | July 14 — August 19 | $3000")
                .endsWith("How much should you pay if you and your brother attend a five-week program?");
        assertThat(question21.options()).isEqualTo("A. $3200\nB. $2470\nC. $3000\nD. $3040");
        assertThat(item(result, 22).stem()).contains("Welcome to join our Summer Youth Language Program");
    }

    @Test
    void splitsClozeOptionsWrittenOnOneLine() throws IOException {
        PaperTextParser.ParsedItem question41 = item(parseFixture(), 41);

        assertThat(question41.options()).isEqualTo("A. old\nB. famous\nC. different\nD. fresh");
        assertThat(question41.stem()).contains("___(41)___");
    }

    @Test
    void numbersTheSevenOutOfSevenBlanksThatCarryNoNumberInTheSource() throws IOException {
        PaperTextParser.Result result = parseFixture();
        PaperTextParser.ParsedItem question36 = item(result, 36);

        assertThat(question36.options()).hasLineCount(7);
        assertThat(question36.options()).startsWith("A. Don't let your friendships die.").contains("G. ");
        assertThat(question36.stem()).contains("How can English be learned well?").contains("___(36)___");
        assertThat(item(result, 40).stem()).contains("___(40)___");
    }

    @Test
    void numbersTheGrammarBlanksAndKeepsTheirHints() throws IOException {
        PaperTextParser.Result result = parseFixture();
        PaperTextParser.ParsedItem question56 = item(result, 56);

        assertThat(question56.options()).isNull();
        assertThat(question56.stem()).contains("___(56)___ (challenge)");
        assertThat(item(result, 65).stem()).contains("___(65)___");
    }

    @Test
    void doesNotMistakeTheWritingRequirementsForQuestions() throws IOException {
        PaperTextParser.Result result = parseFixture();

        // 「1. 表达理解；」「2. 词数 80 左右；」都是写作题的内容要点：只建 76/77 两道写作题，
        // 要点留在题干里，不能变成新的题项（D-23）。
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::number)
                .doesNotHaveDuplicates();
        assertThat(result.items().stream().filter(item -> item.questionType() == null).count())
                .as("客观题仍是 75 道").isEqualTo(75);
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::number)
                .contains(76, 77);
        assertThat(item(result, 76).questionType()).isNotNull();
        assertThat(item(result, 76).stem()).contains("表达理解").contains("假定你是校英文报");
        assertThat(item(result, 77).stem()).contains("社团简介");
    }

    @Test
    void reportsEveryItemAsMissingAnAnswer() throws IOException {
        PaperTextParser.Result result = parseFixture();

        assertThat(result.missingAnswers()).hasSize(75);
    }

    private static BigDecimal pointsOf(PaperTextParser.Result result, int number) {
        return item(result, number).points();
    }

    private static PaperTextParser.ParsedItem item(PaperTextParser.Result result, int number) {
        List<PaperTextParser.ParsedItem> items = result.items();
        return items.stream()
                .filter(parsed -> parsed.number() == number)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有解析出题号 " + number));
    }
}
