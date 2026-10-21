package com.teachloop.paper.importing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * 把上传的试卷文件抽成「按行编号的纯文本」（T186）。
 *
 * <p>只用 JDK 自带能力：{@code java.util.zip} 读 OOXML 容器，
 * {@code javax.xml.parsers} 解析其中的 XML，{@code java.nio.charset} 读 UTF-8 文本。
 * 不新增第三方依赖。</p>
 *
 * <p>支持：{@code .md} / {@code .txt}（UTF-8 直读）、{@code .docx}
 * （{@code word/document.xml} 的 {@code w:p}/{@code w:t}）、{@code .xlsx}
 * （{@code xl/sharedStrings.xml} + 第一个 worksheet）。
 * {@code .doc} 是旧版二进制格式，直接拒绝并提示另存为 {@code .docx}。</p>
 *
 * <p>已知边界：图片、图表、批注、页眉页脚、公式不解析；Word 表格里的段落会被
 * 压平成普通段落；xlsx 的单元格按行拼成一行文本，单元格本身以题号/选项标记
 * 开头时会换行，纯数字的题号单元格会自动补上「. 」。</p>
 */
public final class DocumentTextExtractor {

    /** 一行抽取结果：{@code number} 是从 1 开始、按抽取顺序全局递增的行号。 */
    public record SourceLine(int number, String text) {
    }

    /** 抽取结果：格式标签（md / txt / docx / xlsx）+ 行列表。 */
    public record ExtractedDocument(String format, List<SourceLine> lines) {
    }

    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    private static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    private DocumentTextExtractor() {
    }

    public static ExtractedDocument extract(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ImportException("文件为空，请重新选择文件");
        }
        String name = filename == null ? "" : filename.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return new ExtractedDocument("docx", docxLines(bytes));
        }
        if (name.endsWith(".xlsx")) {
            return new ExtractedDocument("xlsx", xlsxLines(bytes));
        }
        if (name.endsWith(".doc")) {
            throw new ImportException(".doc 是旧版二进制 Word 格式，无法解析；请在 Word 里另存为 .docx 后重新上传");
        }
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            // T231：真实试卷转成 md 后带着标题/加粗/列表/表格记号，先剥干净再进解析器
            return new ExtractedDocument("md", MarkdownSyntax.clean(textLines(decodeUtf8(bytes))));
        }
        if (name.endsWith(".txt")) {
            return new ExtractedDocument("txt", textLines(decodeUtf8(bytes)));
        }
        String shown = name.isBlank() ? "未知文件名" : name;
        throw new ImportException("不支持的文件格式：" + shown + "；仅支持 .docx / .md / .txt / .xlsx");
    }

    // ---------------------------------------------------------------- 纯文本

    private static String decodeUtf8(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static List<SourceLine> textLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<SourceLine> out = new ArrayList<>();
        int lineNo = 1;
        for (String line : normalized.split("\n", -1)) {
            out.add(new SourceLine(lineNo++, line));
        }
        return out;
    }

    // ------------------------------------------------------------------ zip

    private static Map<String, byte[]> readZip(byte[] bytes, String kind) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data = readEntry(zin, entry.getName(), kind);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new ImportException("文件解压后过大（超过 64 MB），请拆分后再导入");
                }
                entries.put(entry.getName().replace('\\', '/'), data);
            }
        } catch (ImportException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ImportException("无法读取 " + kind + " 压缩包：" + ex.getMessage(), ex);
        }
        if (entries.isEmpty()) {
            throw new ImportException("这不是有效的 " + kind + " 文件（zip 结构为空），请确认文件未损坏");
        }
        return entries;
    }

    private static byte[] readEntry(InputStream in, String name, String kind) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_ENTRY_BYTES) {
                throw new ImportException(kind + " 内部条目过大（" + name + "），拒绝解析");
            }
        }
        return out.toByteArray();
    }

    // ----------------------------------------------------------------- docx

    private static List<SourceLine> docxLines(byte[] bytes) {
        Map<String, byte[]> zip = readZip(bytes, ".docx");
        byte[] documentXml = zip.get("word/document.xml");
        if (documentXml == null) {
            throw new ImportException("这不是有效的 .docx：缺少 word/document.xml（可能只是改了扩展名）");
        }
        Document dom = parseXml(documentXml, ".docx");
        NodeList paragraphs = dom.getElementsByTagNameNS(WORD_NS, "p");
        if (paragraphs.getLength() == 0) {
            paragraphs = dom.getElementsByTagNameNS("*", "p");
        }
        List<String> paragraphsText = new ArrayList<>();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            StringBuilder sb = new StringBuilder();
            collectText(paragraphs.item(i), sb);
            paragraphsText.add(sb.toString());
        }
        return numberLines(paragraphsText);
    }

    /** 按段落拼行；段落内的换行（{@code w:br}）拆成独立行，行号全局递增。 */
    private static List<SourceLine> numberLines(List<String> paragraphs) {
        List<SourceLine> out = new ArrayList<>();
        int lineNo = 1;
        for (String paragraph : paragraphs) {
            for (String line : paragraph.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                out.add(new SourceLine(lineNo++, line));
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- xlsx

    private static List<SourceLine> xlsxLines(byte[] bytes) {
        Map<String, byte[]> zip = readZip(bytes, ".xlsx");
        List<String> sharedStrings = readSharedStrings(zip.get("xl/sharedStrings.xml"));
        String sheetName = pickSheet(zip);
        if (sheetName == null) {
            throw new ImportException("这不是有效的 .xlsx：没有找到任何 worksheet");
        }
        Document dom = parseXml(zip.get(sheetName), ".xlsx");
        NodeList rows = dom.getElementsByTagNameNS(SHEET_NS, "row");
        if (rows.getLength() == 0) {
            rows = dom.getElementsByTagNameNS("*", "row");
        }
        List<String> rowTexts = new ArrayList<>();
        for (int i = 0; i < rows.getLength(); i++) {
            Element row = (Element) rows.item(i);
            List<String> cells = new ArrayList<>();
            NodeList children = row.getChildNodes();
            for (int c = 0; c < children.getLength(); c++) {
                Node node = children.item(c);
                if (node instanceof Element cell && "c".equals(localName(cell))) {
                    String value = cellText(cell, sharedStrings);
                    if (value != null && !value.isBlank()) {
                        cells.add(value.strip());
                    }
                }
            }
            if (!cells.isEmpty()) {
                rowTexts.add(joinCells(cells));
            }
        }
        return numberLines(rowTexts);
    }

    private static List<String> readSharedStrings(byte[] xml) {
        List<String> out = new ArrayList<>();
        if (xml == null) {
            return out;
        }
        Document dom = parseXml(xml, ".xlsx");
        NodeList items = dom.getElementsByTagNameNS(SHEET_NS, "si");
        if (items.getLength() == 0) {
            items = dom.getElementsByTagNameNS("*", "si");
        }
        for (int i = 0; i < items.getLength(); i++) {
            StringBuilder sb = new StringBuilder();
            collectText(items.item(i), sb);
            out.add(sb.toString());
        }
        return out;
    }

    private static String pickSheet(Map<String, byte[]> zip) {
        if (zip.containsKey("xl/worksheets/sheet1.xml")) {
            return "xl/worksheets/sheet1.xml";
        }
        return new TreeMap<>(zip).keySet().stream()
                .filter(k -> k.matches("xl/worksheets/[^/]+\\.xml"))
                .findFirst()
                .orElse(null);
    }

    private static String cellText(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            StringBuilder sb = new StringBuilder();
            collectText(cell, sb);
            return sb.toString();
        }
        NodeList values = cell.getElementsByTagNameNS("*", "v");
        if (values.getLength() == 0) {
            return "";
        }
        String raw = values.item(0).getTextContent();
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(raw.strip());
                return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "";
            } catch (NumberFormatException ex) {
                return "";
            }
        }
        return raw == null ? "" : raw;
    }

    /**
     * 一行里的非空单元格拼成行文本：以题号/选项标记开头的单元格另起一行，
     * 纯数字的首个单元格补「. 」。
     */
    private static String joinCells(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String raw : cells) {
            String cell = raw.strip();
            if (cell.isEmpty()) {
                continue;
            }
            if (first && cell.matches("\\d+")) {
                cell = cell + ". ";
            }
            boolean marker = cell.matches("^(?:[A-Ga-g]|\\d+)\\s*[.、．)）]\\s*.*");
            if (!first) {
                sb.append(marker ? '\n' : ' ');
            }
            sb.append(cell);
            first = false;
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- XML 公用

    private static Document parseXml(byte[] bytes, String kind) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                    // 忽略 warning
                }

                @Override
                public void error(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }
            });
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (SAXParseException ex) {
            throw new ImportException(kind + " 内部的 XML 无法解析（第 " + ex.getLineNumber() + " 行）："
                    + ex.getMessage(), ex);
        } catch (ImportException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportException(kind + " 内部的 XML 无法解析：" + ex.getMessage(), ex);
        }
    }

    private static String localName(Node node) {
        String name = node.getLocalName();
        if (name != null) {
            return name;
        }
        String nodeName = node.getNodeName();
        int colon = nodeName.indexOf(':');
        return colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
    }

    /** 深度优先收集文本：{@code t} 取内容，{@code tab} 变制表符，{@code br}/{@code cr} 变换行。 */
    private static void collectText(Node node, StringBuilder sb) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                switch (localName(element)) {
                    case "t" -> sb.append(element.getTextContent());
                    case "tab" -> sb.append('\t');
                    case "br", "cr" -> sb.append('\n');
                    case "rPh", "delText", "instrText" -> {
                        // 拼音注音、修订删除、域代码不进入题干
                    }
                    default -> collectText(element, sb);
                }
            } else if (child.getNodeType() == Node.TEXT_NODE) {
                sb.append(child.getNodeValue());
            }
        }
    }
}
