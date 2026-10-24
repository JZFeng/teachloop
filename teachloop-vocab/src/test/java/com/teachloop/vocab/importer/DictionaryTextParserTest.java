package com.teachloop.vocab.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T390：解析器与 spec §15.1/§15.2 逐条对应。 */
class DictionaryTextParserTest {

    private final DictionaryTextParser parser = new DictionaryTextParser();

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("..", "specs", "004-vocab", "fixtures", "import", name));
    }

    @Test
    @DisplayName("固定夹具 sample-4-formats.md：40 token / 去重 10 / 跳过 4 行说明文字")
    void fourFormatsFixture() throws IOException {
        ParseResult result = parser.parse(fixture("sample-4-formats.md"), "en");
        assertThat(result.tokens()).hasSize(40);
        assertThat(result.distinctNormalized()).hasSize(10);
        assertThat(result.skipped()).hasSize(4);
        assertThat(result.skipped()).extracting(ParseResult.SkippedLine::lineNumber)
                .containsExactly(1, 4, 7, 19);
        assertThat(result.skipped()).allSatisfy(line ->
                assertThat(line.reason()).contains("中日韩"));
        assertThat(result.lineCount()).isEqualTo(23);
    }

    @Test
    @DisplayName("CRLF 与 LF 解析结果一致")
    void crlfAndLf() {
        ParseResult crlf = parser.parse("apple, brave\r\nclever\r\ndream", "en");
        ParseResult lf = parser.parse("apple, brave\nclever\ndream", "en");
        assertThat(crlf.tokens()).extracting(ParseResult.ParsedToken::raw)
                .containsExactly("apple", "brave", "clever", "dream");
        assertThat(lf.tokens()).isEqualTo(crlf.tokens());
        assertThat(crlf.lineCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("全角逗号、分号、顿号都可作分隔符")
    void fullWidthSeparators() {
        ParseResult result = parser.parse("apple，brave；clever、dream", "en");
        assertThat(result.tokens()).extracting(ParseResult.ParsedToken::raw)
                .containsExactly("apple", "brave", "clever", "dream");
    }

    @Test
    @DisplayName("引号内的空格保留（take off 是一个词）")
    void quotedPhrase() {
        ParseResult result = parser.parse("\"take off\", apple, “give up”", "en");
        assertThat(result.tokens()).extracting(ParseResult.ParsedToken::raw)
                .containsExactly("take off", "apple", "give up");
    }

    @Test
    @DisplayName("行尾分隔符与多余空白被忽略")
    void trailingSeparators() {
        assertThat(parser.parse("apple, brave,  \n  clever ;;", "en").tokens())
                .extracting(ParseResult.ParsedToken::raw)
                .containsExactly("apple", "brave", "clever");
    }

    @Test
    @DisplayName("列表标记 - * + 1. 1) 去掉后按普通词行解析")
    void listMarkers() {
        ParseResult result = parser.parse("- apple\n* brave\n1. clever\n2) dream", "en");
        assertThat(result.tokens()).extracting(ParseResult.ParsedToken::raw)
                .containsExactly("apple", "brave", "clever", "dream");
    }

    @Test
    @DisplayName("标题与引用跳过，带行号与原因；其余行照常")
    void headingsAndQuotes() {
        ParseResult result = parser.parse("# 标题\n> 引用\napple", "en");
        assertThat(result.tokens()).extracting(ParseResult.ParsedToken::raw).containsExactly("apple");
        assertThat(result.skipped()).extracting(ParseResult.SkippedLine::lineNumber)
                .containsExactly(1, 2);
        assertThat(result.skipped()).allSatisfy(line -> assertThat(line.reason()).contains("标题"));
    }

    @Test
    @DisplayName("代码围栏切换状态，块内行按普通词行解析")
    void codeFence() {
        ParseResult result = parser.parse("```\napple\nbrave\n```\nclever", "en");
        assertThat(result.tokens()).extracting(ParseResult.ParsedToken::raw)
                .containsExactly("apple", "brave", "clever");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    @DisplayName("表格：表头与分隔行跳过，数据行按单元格出 token")
    void tableRows() {
        ParseResult result = parser.parse("""
                | word | meaning |
                | --- | --- |
                | apple | 苹果 |
                | brave | 勇敢 |
                """, "en");
        assertThat(result.tokens()).extracting(ParseResult.ParsedToken::raw)
                .containsExactly("apple", "苹果", "brave", "勇敢");
        assertThat(result.skipped()).extracting(ParseResult.SkippedLine::reason)
                .containsExactly("Markdown 表格表头（用于列映射）", "Markdown 表格分隔行");
    }

    @Test
    @DisplayName("语言 en：整行含中日韩字符按说明文字跳过")
    void cjkLineSkippedForEnglish() {
        ParseResult result = parser.parse("apple 苹果\nbrave", "en");
        assertThat(result.tokens()).extracting(ParseResult.ParsedToken::raw).containsExactly("brave");
        assertThat(result.skipped()).singleElement()
                .satisfies(line -> assertThat(line.text()).contains("苹果"));
    }

    @Test
    @DisplayName("语言 zh：纯 ASCII 短行不擅自丢弃，标为待确认")
    void asciiNoteNeedsConfirmForChinese() {
        ParseResult result = parser.parse("apple brave\n中文词", "zh");
        assertThat(result.tokens()).extracting(ParseResult.ParsedToken::raw).containsExactly("中文词");
        assertThat(result.pending()).singleElement()
                .satisfies(line -> assertThat(line.text()).isEqualTo("apple brave"));
    }

    @Test
    @DisplayName("去重按小写与去空格：apple / Apple / APPLE 是同一个词")
    void duplicateCounting() {
        ParseResult result = parser.parse("apple, Apple, APPLE", "en");
        assertThat(result.tokens()).hasSize(3);
        assertThat(result.distinctNormalized()).containsExactly("apple");
    }

    @Test
    @DisplayName("空文本与只有空行的文本不报错")
    void emptyInputs() {
        assertThat(parser.parse("", "en").tokens()).isEmpty();
        ParseResult blanks = parser.parse("\n\n  \n", "en");
        assertThat(blanks.tokens()).isEmpty();
        assertThat(blanks.skipped()).isEmpty();
    }

    @Test
    @DisplayName("T391：BOM 被去掉并留下清洗记录")
    void bomCleanup() {
        ParseResult result = parser.parse("\uFEFFapple", "en");
        assertThat(result.tokens()).extracting(ParseResult.ParsedToken::raw).containsExactly("apple");
        assertThat(result.cleanups()).singleElement().satisfies(cleanup -> {
            assertThat(cleanup.rule()).isEqualTo("去 BOM");
            assertThat(cleanup.lineNumber()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("T391：行首序号/项目符号与连续空格可追溯")
    void markerAndSpacesCleanup() {
        ParseResult result = parser.parse("1. apple   brave", "en");
        assertThat(result.tokens()).extracting(ParseResult.ParsedToken::raw)
                .containsExactly("apple", "brave");
        assertThat(result.cleanups()).extracting(ParseResult.Cleanup::rule)
                .contains("去行首序号/项目符号", "去多余空格");
    }

    @Test
    @DisplayName("T391：每条清洗记录都带行号、规则与非空 before")
    void cleanupsAreTraceable() {
        ParseResult result = parser.parse("\uFEFF\"take off\", apple n.，brave", "en");
        assertThat(result.cleanups()).isNotEmpty();
        assertThat(result.cleanups()).allSatisfy(cleanup -> {
            assertThat(cleanup.lineNumber()).isGreaterThanOrEqualTo(1);
            assertThat(cleanup.rule()).isNotBlank();
            assertThat(cleanup.before()).isNotBlank();
        });
        assertThat(result.tokens()).extracting(ParseResult.ParsedToken::raw)
                .containsExactly("take off", "apple", "brave");
    }
}
