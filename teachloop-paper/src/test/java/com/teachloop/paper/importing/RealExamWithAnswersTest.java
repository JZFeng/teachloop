package com.teachloop.paper.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * T236：拿老师真实交付的两份文件做回归。
 *
 * <p>两种交付方式都要吃得下：</p>
 * <ol>
 *   <li>卷面 {@code paper.md} 单独导入，答案 {@code answers.md} 粘进答案表 ——
 *       原文里带 {@code ---}、{@code **}、{@code 1-5: A B B C C}、以及带中文解析的
 *       填空题答案；</li>
 *   <li>卷面与答案写在同一份文档里（{@code paper and answer.md}），卷末是
 *       {@code 试卷参考答案与解析 (卷末附录)} 的附录 —— 标题不叫「答案」也要认。</li>
 * </ol>
 *
 * <p>断言的是最终结果：75 题、4 个部分、120 分、**75 题全部拿到标准答案**
 * （其中 56–75 是填空题，答案是文本而不是字母）。</p>
 */
class RealExamWithAnswersTest {

    private static byte[] read(String name) throws IOException {
        try (InputStream in = RealExamWithAnswersTest.class.getResourceAsStream("/exam/" + name)) {
            assertThat(in).as("找不到 /exam/%s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static PaperTextParser.Result parseCombined() throws IOException {
        DocumentTextExtractor.ExtractedDocument document =
                DocumentTextExtractor.extract("paper and answer.md", read("english-grade10-2026-with-answers.md"));
        return PaperTextParser.parse(document.lines());
    }

    @Test
    void importsTheWholePaperWithItsAnswerAppendix() throws IOException {
        PaperTextParser.Result result = parseCombined();

        assertThat(result.sections()).extracting(PaperTextParser.ParsedSection::title)
                .containsExactly("第一部分 听力", "第二部分 阅读理解", "第三部分 英语知识运用", "第四部分 写作");
        // D-23：第四部分写作不再跳过，按「第X节」建两道 ESSAY 题（76 / 77）
        assertThat(result.sections()).extracting(section -> section.items().size())
                .containsExactly(20, 20, 35, 2);
        assertThat(result.items()).hasSize(77);
        assertThat(result.items()).filteredOn(item -> item.questionType() != null)
                .extracting(PaperTextParser.ParsedItem::number).containsExactly(76, 77);
        assertThat(result.missingAnswers()).as("附录里的答案要全部配上；写作题不算缺答案").isEmpty();

        BigDecimal total = result.items().stream()
                .map(PaperTextParser.ParsedItem::points)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).as("客观 120 + 写作 30").isEqualByComparingTo("150");
    }

    @Test
    void readsMultipleChoiceAnswersFromTheAppendix() throws IOException {
        Map<Integer, String> answers = parseCombined().items().stream()
                .filter(item -> item.questionType() == null)
                .collect(Collectors.toMap(PaperTextParser.ParsedItem::number,
                        PaperTextParser.ParsedItem::answerKey, (first, second) -> first, TreeMap::new));

        assertThat(answers).containsEntry(1, "A").containsEntry(2, "B").containsEntry(5, "C");
        assertThat(answers).containsEntry(21, "D").containsEntry(23, "A");
        assertThat(answers).containsEntry(36, "G").containsEntry(40, "F");
        assertThat(answers).containsEntry(41, "A").containsEntry(55, "C");
    }

    @Test
    void readsFillBlankAnswersAsTextNotLetters() throws IOException {
        Map<Integer, String> answers = parseCombined().items().stream()
                .filter(item -> item.questionType() == null)
                .collect(Collectors.toMap(PaperTextParser.ParsedItem::number,
                        PaperTextParser.ParsedItem::answerKey, (first, second) -> first, TreeMap::new));

        // 这些答案以 a–g 开头，绝不能变成字母
        assertThat(answers).containsEntry(56, "challenging");
        assertThat(answers).containsEntry(57, "anxiety");
        assertThat(answers).containsEntry(58, "for");
        assertThat(answers).containsEntry(59, "the");
        assertThat(answers).containsEntry(66, "to walk");
        assertThat(answers).containsEntry(75, "graduating");
    }

    @Test
    void answerTablePathAlsoCoversAllSeventyFive() throws IOException {
        DocumentTextExtractor.ExtractedDocument paper =
                DocumentTextExtractor.extract("paper.md", read("english-grade10-2026.md"));
        PaperTextParser.Result parsed = PaperTextParser.parse(paper.lines());
        assertThat(parsed.items()).hasSize(77);

        Set<Integer> fillBlank = parsed.items().stream()
                .filter(item -> item.questionType() == null)
                .filter(item -> item.options() == null || item.options().isBlank())
                .map(PaperTextParser.ParsedItem::number)
                .collect(Collectors.toSet());
        assertThat(fillBlank).as("56–75 是填空题").hasSize(20);
        Set<Integer> essays = parsed.items().stream()
                .filter(item -> item.questionType() != null)
                .map(PaperTextParser.ParsedItem::number)
                .collect(Collectors.toSet());

        String answerText = new String(read("english-grade10-2026-answers.md"), StandardCharsets.UTF_8);
        AnswerTableParser.Result table = AnswerTableParser.parse(answerText, fillBlank, essays);

        Map<Integer, String> answers = table.entries().stream()
                .collect(Collectors.toMap(AnswerTableParser.Entry::number, AnswerTableParser.Entry::answer));
        assertThat(answers).as("原文件的装饰（---、**、中文解析）都要能吃掉").hasSize(75);
        assertThat(answers).containsEntry(1, "A").containsEntry(35, "D");
        assertThat(answers).containsEntry(56, "challenging").containsEntry(75, "graduating");
        List<Integer> numbers = answers.keySet().stream().sorted().toList();
        assertThat(numbers).first().isEqualTo(1);
        assertThat(numbers).last().isEqualTo(75);
    }
}
