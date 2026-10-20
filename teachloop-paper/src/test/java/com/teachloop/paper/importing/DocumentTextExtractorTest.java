package com.teachloop.paper.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * T186：只用 JDK 的 {@code ZipOutputStream} 造最小 .docx / .xlsx，
 * 验证抽取逻辑（不依赖任何 Office 库或 Testcontainers）。
 */
class DocumentTextExtractorTest {

    // ------------------------------------------------------------------ md/txt

    @Test
    void readsUtf8MarkdownDirectly() {
        String text = "1. 题目\nA. 甲\nB. 乙\n答案\n1. A\n";
        DocumentTextExtractor.ExtractedDocument document =
                DocumentTextExtractor.extract("paper.md", text.getBytes(StandardCharsets.UTF_8));

        assertThat(document.format()).isEqualTo("md");
        assertThat(document.lines()).hasSize(6);
        assertThat(document.lines().get(0).number()).isEqualTo(1);
        assertThat(document.lines().get(0).text()).isEqualTo("1. 题目");
        assertThat(document.lines().get(4).text()).isEqualTo("1. A");
        assertThat(document.lines().get(5).text()).isEmpty();
    }

    @Test
    void stripsUtf8BomFromTextFiles() {
        byte[] body = "\uFEFF1. 题目\n".getBytes(StandardCharsets.UTF_8);
        DocumentTextExtractor.ExtractedDocument document =
                DocumentTextExtractor.extract("paper.txt", body);

        assertThat(document.format()).isEqualTo("txt");
        assertThat(document.lines().get(0).text()).isEqualTo("1. 题目");
    }

    // -------------------------------------------------------------------- docx

    @Test
    void extractsParagraphsFromMinimalDocxAndPairsAnswers() throws IOException {
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>1. 中国的首都是哪里？</w:t></w:r></w:p>
                    <w:p><w:r><w:t>A. 北京</w:t></w:r></w:p>
                    <w:p><w:r><w:t>B. 上海</w:t></w:r></w:p>
                    <w:p><w:r><w:t>2. 水的化学式是什么？</w:t></w:r></w:p>
                    <w:p><w:r><w:t>A. H2O</w:t></w:r></w:p>
                    <w:p><w:r><w:t>B. CO2</w:t></w:r></w:p>
                    <w:p><w:r><w:t>答案</w:t></w:r></w:p>
                    <w:p><w:r><w:t>1. A</w:t></w:r></w:p>
                    <w:p><w:r><w:t>2. A</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """;
        byte[] docx = zip(Map.of("word/document.xml", documentXml));

        DocumentTextExtractor.ExtractedDocument document =
                DocumentTextExtractor.extract("paper.docx", docx);
        PaperTextParser.Result result = PaperTextParser.parse(document.lines());

        assertThat(document.format()).isEqualTo("docx");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "A");
        assertThat(result.items().get(0).options()).isEqualTo("A. 北京\nB. 上海");
        assertThat(result.missingAnswers()).isEmpty();
    }

    @Test
    void rejectsDocFormatWithActionableMessage() {
        assertThatThrownBy(() -> DocumentTextExtractor.extract("paper.doc", "舊格式".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("另存为 .docx");
    }

    @Test
    void rejectsCorruptZipWithReadableReason() {
        assertThatThrownBy(() -> DocumentTextExtractor.extract("broken.docx",
                "this is definitely not a zip".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("docx");
    }

    @Test
    void rejectsDocxWhoseDocumentXmlIsBroken() throws IOException {
        byte[] docx = zip(Map.of("word/document.xml", "<w:document><w:body><w:p>"));

        assertThatThrownBy(() -> DocumentTextExtractor.extract("broken.docx", docx))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("XML");
    }

    @Test
    void rejectsDocxWithoutDocumentXml() throws IOException {
        byte[] docx = zip(Map.of("word/styles.xml", "<styles/>"));

        assertThatThrownBy(() -> DocumentTextExtractor.extract("renamed.docx", docx))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("word/document.xml");
    }

    @Test
    void rejectsUnsupportedFormat() {
        assertThatThrownBy(() -> DocumentTextExtractor.extract("paper.pdf", "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("不支持的文件格式");
    }

    // -------------------------------------------------------------------- xlsx

    @Test
    void extractsSharedStringsAndSheetRowsAndPairsAnswers() throws IOException {
        String sharedStrings = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="9" uniqueCount="9">
                  <si><t>1</t></si>
                  <si><t>中国的首都是哪里？</t></si>
                  <si><t>A. 北京</t></si>
                  <si><t>B. 上海</t></si>
                  <si><t>2</t></si>
                  <si><t>水的化学式是什么？</t></si>
                  <si><t>A. H2O</t></si>
                  <si><t>B. CO2</t></si>
                  <si><t>答案</t></si>
                </sst>
                """;
        String sheet1 = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="s"><v>0</v></c>
                      <c r="B1" t="s"><v>1</v></c>
                      <c r="C1" t="s"><v>2</v></c>
                      <c r="D1" t="s"><v>3</v></c>
                    </row>
                    <row r="2">
                      <c r="A2" t="s"><v>4</v></c>
                      <c r="B2" t="s"><v>5</v></c>
                      <c r="C2" t="s"><v>6</v></c>
                      <c r="D2" t="s"><v>7</v></c>
                    </row>
                    <row r="3"><c r="A3" t="s"><v>8</v></c></row>
                    <row r="4"><c r="A4" t="inlineStr"><is><t>1-2 AA</t></is></c></row>
                  </sheetData>
                </worksheet>
                """;
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("xl/sharedStrings.xml", sharedStrings);
        entries.put("xl/worksheets/sheet1.xml", sheet1);
        byte[] xlsx = zip(entries);

        DocumentTextExtractor.ExtractedDocument document =
                DocumentTextExtractor.extract("paper.xlsx", xlsx);
        PaperTextParser.Result result = PaperTextParser.parse(document.lines());

        assertThat(document.format()).isEqualTo("xlsx");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).stem()).isEqualTo("中国的首都是哪里？");
        assertThat(result.items().get(0).options()).isEqualTo("A. 北京\nB. 上海");
        assertThat(result.items()).extracting(PaperTextParser.ParsedItem::answerKey)
                .containsExactly("A", "A");
        assertThat(result.missingAnswers()).isEmpty();
    }

    @Test
    void rejectsXlsxWithoutWorksheet() throws IOException {
        byte[] xlsx = zip(Map.of("xl/sharedStrings.xml", "<sst/>"));

        assertThatThrownBy(() -> DocumentTextExtractor.extract("broken.xlsx", xlsx))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("worksheet");
    }

    // ------------------------------------------------------------------ helper

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
