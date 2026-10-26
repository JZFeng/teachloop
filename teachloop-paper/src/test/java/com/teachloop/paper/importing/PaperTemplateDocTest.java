package com.teachloop.paper.importing;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.paper.domain.QuestionType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 模板文档不漂移（T473）：{@code docs/templates/paper.md} 必须真的按写出来的样子解析。
 *
 * <p>模板是给老师照着抄的，解析规则一改（题号、选项、答案区、写作要点里的分值），
 * 最先过期的就是它。这里直接读文档、跑真解析器，把「文档里写的」和「代码能读的」钉在一起。</p>
 */
class PaperTemplateDocTest {

    private static final Path TEMPLATE = Path.of("..", "docs", "templates", "paper.md");

    @Test
    void templateParsesAsDocumented() throws Exception {
        String text = Files.readString(TEMPLATE, StandardCharsets.UTF_8);
        DocumentTextExtractor.ExtractedDocument document =
                DocumentTextExtractor.extract("paper.md", text.getBytes(StandardCharsets.UTF_8));
        PaperTextParser.Result result = PaperTextParser.parse(document.lines());

        PaperHeaderMeta.Hints hints = PaperHeaderMeta.parse(document.lines());
        assertThat(hints.authorName()).isEqualTo("李璐");
        assertThat(hints.reviewerName()).isEqualTo("刘嘉伦");
        assertThat(hints.targetTotalPoints().stripTrailingZeros().toPlainString()).isEqualTo("20");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.missingAnswers()).isEmpty();
        assertThat(result.sections()).extracting(PaperTextParser.ParsedSection::title)
                .containsExactly("第一部分 听力", "第二部分 阅读理解", "第三部分 写作");

        List<PaperTextParser.ParsedItem> items = result.items();
        assertThat(items).extracting(PaperTextParser.ParsedItem::number).containsExactly(1, 2, 3, 4);
        assertThat(items).extracting(item -> item.points().stripTrailingZeros().toPlainString())
                .containsExactly("1.5", "1.5", "2", "15");
        assertThat(items).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "B", "C", null);
        assertThat(items.get(0).options()).isEqualTo("""
                A. A comedy film.
                B. A horror film.
                C. A historical film.""".strip());

        // 写作题：没有单独写题号，按「当前最大题号 + 1」接在客观题后面；正文里带分值的要点行自动变成 rubric。
        PaperTextParser.ParsedItem essay = items.get(3);
        assertThat(essay.questionType()).isEqualTo(QuestionType.ESSAY);
        assertThat(essay.rubricMarkdown()).isEqualTo("""
                - 表达理解（5 分）
                - 给出建议（5 分）
                - 语法与拼写（5 分）""");
    }
}
