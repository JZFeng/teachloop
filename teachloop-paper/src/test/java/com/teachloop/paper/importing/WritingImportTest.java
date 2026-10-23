package com.teachloop.paper.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T363：写作题建题、题面分值语法、参考范文绑定（D-23 / D-25 / D-27）。 */
class WritingImportTest {

    private static byte[] read(String name) throws IOException {
        try (InputStream in = WritingImportTest.class.getResourceAsStream("/exam/" + name)) {
            assertThat(in).as("找不到 /exam/%s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("带题号的写作任务：一行一题、分值取「满分 N 分」、答案区范文按题号绑定")
    void numberedWritingTasksBecomeEssayItemsWithReference() throws IOException {
        DocumentTextExtractor.ExtractedDocument doc =
                DocumentTextExtractor.extract("writing-numbered.md", read("english-grade10-writing-numbered.md"));
        PaperTextParser.Result result = PaperTextParser.parse(doc.lines());

        List<PaperTextParser.ParsedItem> essays = result.items().stream()
                .filter(item -> item.questionType() != null)
                .toList();
        assertThat(essays).extracting(PaperTextParser.ParsedItem::number).containsExactly(15, 16);
        assertThat(essays).extracting(PaperTextParser.ParsedItem::points)
                .containsExactly(new BigDecimal("15"), new BigDecimal("15"));
        assertThat(essays.get(0).rubricMarkdown()).as("题面要点没有分值，不预填 rubric").isNull();
        assertThat(essays.get(0).referenceMarkdown()).startsWith("Dear Chen Ming,");
        assertThat(essays.get(1).referenceMarkdown()).startsWith("Good morning, everyone!");
        assertThat(result.missingAnswers()).isEmpty();
    }

    @Test
    @DisplayName("有「第X节」的写作部分：一节一题，题号接在全卷最大题号之后")
    void sectionedWritingTasksBecomeEssayItems() throws IOException {
        DocumentTextExtractor.ExtractedDocument doc = DocumentTextExtractor.extract("paper and answer.md",
                read("english-grade10-2026-with-answers.md"));
        PaperTextParser.Result result = PaperTextParser.parse(doc.lines());

        assertThat(result.items()).hasSize(77);
        assertThat(result.items().stream().filter(i -> i.questionType() != null)
                .map(PaperTextParser.ParsedItem::number).toList()).containsExactly(76, 77);
        BigDecimal total = result.items().stream().map(PaperTextParser.ParsedItem::points)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("题面分值语法：(5 points) / （5分） / N pts / points each 都能解析")
    void parsesPointsInChineseAndEnglish() {
        String text = """
                # 分值语法
                ## 第一部分 听力（共 2 小题；每小题 1.5 points）
                1. What film?
                A. A comedy film.
                B. A horror film.
                2. Where?
                A. At a hotel.
                B. At a school.

                ## 第四部分 写作（共两节，满分 30 points）
                ## 第一节 应用文写作（15 points）
                假定你是李华，给朋友写一封回信。
                - 表达理解 (5 points)
                - 给出建议（5分）
                - 词数 80 左右 (2 pts)
                - 无语法错误（3分）
                **Dear Chen Ming:**
                ______

                ## 第二节 口语表达（15 points）
                写一篇招新演讲稿。
                - 社团简介 (5 points)
                - 社团主要活动 (5 points)
                - 词数 80 左右 (2 points)
                - 无语法错误 (3 points)
                **Good morning!**
                """;

        PaperTextParser.Result result = PaperTextParser.parse(lines(text));

        assertThat(result.items().get(0).points()).isEqualByComparingTo("1.5");
        List<PaperTextParser.ParsedItem> essays = result.items().stream()
                .filter(item -> item.questionType() != null)
                .toList();
        assertThat(essays).hasSize(2);
        assertThat(essays.get(0).points()).isEqualByComparingTo("15");
        assertThat(essays.get(1).points()).isEqualByComparingTo("15");
        assertThat(essays.get(0).rubricMarkdown())
                .contains("- 表达理解 (5 points)")
                .contains("- 给出建议（5分）")
                .contains("- 词数 80 左右 (2 pts)")
                .contains("- 无语法错误（3分）");
        assertThat(essays.get(1).rubricMarkdown()).contains("- 社团简介 (5 points)");
    }

    @Test
    @DisplayName("答案区范文里以数字开头的行不再报错；客观题题号仍按原规则报错")
    void numberedLinesInsideEssaySampleAreNotAnswers() {
        String text = """
                # 写作卷
                ## 第四部分 写作（共两节，满分 30 分）
                15. 应用文写作（满分 15 分）
                写一封回信。

                # 试卷参考答案
                1. B
                15. 应用文写作示范范文：
                Dear Chen Ming,
                1. I understand your feeling.
                2. You should talk with your parents.
                """;

        PaperTextParser.Result result = PaperTextParser.parse(lines(text));
        PaperTextParser.ParsedItem essay = result.items().stream()
                .filter(item -> item.questionType() != null).findFirst().orElseThrow();
        assertThat(essay.referenceMarkdown())
                .contains("1. I understand your feeling.")
                .contains("2. You should talk with your parents.");
        assertThat(result.missingAnswers()).isEmpty();
    }

    @Test
    @DisplayName("粘贴答案表：写作题号与范文整块跳过并提醒，不把范文当字母答案")
    void answerTableSkipsEssaySamples() {
        String text = """
                1 A
                2 B
                15. 应用文写作示范范文：
                Dear Chen Ming,
                1. I understand your feeling.
                """;

        AnswerTableParser.Result result = AnswerTableParser.parse(text, Set.of(), Set.of(15));

        Map<Integer, String> answers = result.entries().stream()
                .collect(Collectors.toMap(AnswerTableParser.Entry::number, AnswerTableParser.Entry::answer,
                        (a, b) -> a, TreeMap::new));
        assertThat(answers).containsExactly(Map.entry(1, "A"), Map.entry(2, "B"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("15") && w.contains("写作题"));
    }

    private static List<DocumentTextExtractor.SourceLine> lines(String text) {
        String[] rows = text.split("\n", -1);
        List<DocumentTextExtractor.SourceLine> out = new java.util.ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            out.add(new DocumentTextExtractor.SourceLine(i + 1, rows[i]));
        }
        return MarkdownSyntaxAccess.clean(out);
    }

    /** 真实入口会先过 MarkdownSyntax（它包内可见）；测试也走同一条路径。 */
    private static final class MarkdownSyntaxAccess {
        private static List<DocumentTextExtractor.SourceLine> clean(
                List<DocumentTextExtractor.SourceLine> lines) {
            return MarkdownSyntax.clean(lines);
        }
    }
}
