package com.teachloop.vocab.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 词表文本解析器（T390 / T391 / FR-108 / spec §15）：CSV、MD、TXT 与「粘贴加词」共用同一个入口。
 *
 * <p>行类型按 spec §15.1 处理：空行跳过；`#` 标题与 `>` 引用当说明文字跳过；
 * ``` 围栏切换代码块（块内按普通词行）；表格行按单元格切、分隔行跳过、首个非分隔行当表头；
 * 列表标记去掉后按普通词行；普通行再按「逗号（中英文）/ 分号 / 空白」切分，
 * 忽略空 token 与行尾分隔符，引号内的空格保留（`"take off"`）。
 *
 * <p>格式清洗（T391）自动执行但逐条留痕：BOM、行首序号/项目符号、连续/首尾空格在这里处理，
 * 全角标点、引号、音标、词性标注在 {@link ImportTextCleaner} 里处理；全部进入
 * {@link ParseResult#cleanups()}（行号 + 规则 + 前后值）。
 *
 * <p>说明文字规则（spec §15.2）：语言 {@code en} 时整行含中日韩字符即跳过；
 * 语言 {@code zh} 时纯 ASCII 短行不擅自丢弃，标为 {@link ParseResult.PendingLine} 交预览页确认。
 * 所有跳过都在结果里带行号与原文，不静默。列映射（有表头时哪一列是正面/释义）留给 T393。
 */
@Component
public class DictionaryTextParser {

    public static final String LANGUAGE_EN = "en";
    public static final String LANGUAGE_ZH = "zh";

    private static final Pattern LIST_MARKER = Pattern.compile("^(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile("^:?-{3,}:?$");
    private static final Pattern REPEATED_SPACES = Pattern.compile("[ \\t]{2,}");

    public ParseResult parse(String text) {
        return parse(text, LANGUAGE_EN);
    }

    public ParseResult parse(String text, String language) {
        String lang = language == null ? LANGUAGE_EN : language.strip().toLowerCase(Locale.ROOT);
        List<ParseResult.ParsedToken> tokens = new ArrayList<>();
        List<ParseResult.SkippedLine> skipped = new ArrayList<>();
        List<ParseResult.PendingLine> pending = new ArrayList<>();
        List<ParseResult.Cleanup> cleanups = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return new ParseResult(0, tokens, skipped, pending, cleanups);
        }

        String source = text;
        if (source.startsWith("\uFEFF")) {
            cleanups.add(new ParseResult.Cleanup(1, "去 BOM", "\uFEFF", ""));
            source = source.substring(1);
        }
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int lineCount = lines.length;
        if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
            lineCount--;
        }
        boolean inFence = false;
        boolean inTable = false;
        boolean tableHeaderSeen = false;

        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String raw = lines[index];
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (inFence) {
                if (trimmed.startsWith("```")) {
                    inFence = false;
                } else {
                    parseWordLine(lineNumber, raw, trimmed, lang, tokens, skipped, pending, cleanups);
                }
                continue;
            }
            if (trimmed.startsWith("```")) {
                inFence = true;
                continue;
            }
            if (trimmed.startsWith("|")) {
                if (!inTable) {
                    inTable = true;
                    tableHeaderSeen = false;
                }
                List<String> cells = tableCells(trimmed);
                boolean separator = !cells.isEmpty() && cells.stream()
                        .allMatch(cell -> TABLE_SEPARATOR_CELL.matcher(cell).matches());
                if (separator) {
                    skipped.add(new ParseResult.SkippedLine(lineNumber, raw, "Markdown 表格分隔行"));
                    continue;
                }
                if (!tableHeaderSeen) {
                    tableHeaderSeen = true;
                    skipped.add(new ParseResult.SkippedLine(lineNumber, raw, "Markdown 表格表头（用于列映射）"));
                    continue;
                }
                for (String cell : cells) {
                    String cleaned = ImportTextCleaner.cleanToken(cell, lineNumber, cleanups);
                    if (cleaned != null && !cleaned.isEmpty()) {
                        tokens.add(new ParseResult.ParsedToken(cleaned, lineNumber));
                    }
                }
                continue;
            }
            inTable = false;
            tableHeaderSeen = false;

            if (trimmed.startsWith("#") || trimmed.startsWith(">")) {
                skipped.add(new ParseResult.SkippedLine(lineNumber, raw, "Markdown 标题/引用（说明文字）"));
                continue;
            }
            String content = collapseSpaces(lineNumber, raw, trimmed, cleanups);
            var marker = LIST_MARKER.matcher(content);
            if (marker.find()) {
                String withoutMarker = content.substring(marker.end()).strip();
                cleanups.add(new ParseResult.Cleanup(
                        lineNumber, "去行首序号/项目符号", content, withoutMarker));
                content = withoutMarker;
            }
            if (content.isEmpty()) {
                skipped.add(new ParseResult.SkippedLine(lineNumber, raw, "只有列表标记，没有单词"));
                continue;
            }
            parseWordLine(lineNumber, raw, content, lang, tokens, skipped, pending, cleanups);
        }
        return new ParseResult(lineCount, tokens, skipped, pending, cleanups);
    }

    private void parseWordLine(int lineNumber, String raw, String content, String language,
                               List<ParseResult.ParsedToken> tokens,
                               List<ParseResult.SkippedLine> skipped,
                               List<ParseResult.PendingLine> pending,
                               List<ParseResult.Cleanup> cleanups) {
        if (LANGUAGE_EN.equals(language) && containsCjk(content)) {
            skipped.add(new ParseResult.SkippedLine(
                    lineNumber, content, "含中日韩字符，按说明文字跳过（spec §15.2）"));
            return;
        }
        if (LANGUAGE_ZH.equals(language) && !containsCjk(content) && looksLikeAsciiNote(content)) {
            pending.add(new ParseResult.PendingLine(
                    lineNumber, content, "语言为 zh 时，纯 ASCII 短行可能是外语说明，需在预览页确认"));
            return;
        }
        ImportTextCleaner.CleanedLine cleaned = ImportTextCleaner.cleanLine(content, lineNumber);
        if (cleaned.tokens().isEmpty()) {
            skipped.add(new ParseResult.SkippedLine(lineNumber, raw, "没有解析出单词"));
            return;
        }
        cleanups.addAll(cleaned.cleanups());
        for (String word : cleaned.tokens()) {
            tokens.add(new ParseResult.ParsedToken(word, lineNumber));
        }
    }

    /** 首尾/连续空格清洗；返回用于后续解析的文本。 */
    private static String collapseSpaces(int lineNumber, String raw, String content,
                                         List<ParseResult.Cleanup> cleanups) {
        String collapsed = REPEATED_SPACES.matcher(content).replaceAll(" ");
        if (!collapsed.equals(content) || !raw.equals(raw.strip())) {
            cleanups.add(new ParseResult.Cleanup(lineNumber, "去多余空格", raw, collapsed));
        }
        return collapsed;
    }

    private static List<String> tableCells(String line) {
        List<String> cells = new ArrayList<>();
        for (String cell : line.split("\\|", -1)) {
            String cleaned = cell.strip();
            if (cleaned.isEmpty()) {
                continue;
            }
            if (cleaned.length() >= 2) {
                char first = cleaned.charAt(0);
                char last = cleaned.charAt(cleaned.length() - 1);
                if ((first == '"' && last == '"') || (first == '\u201C' && last == '\u201D')) {
                    cleaned = cleaned.substring(1, cleaned.length() - 1).strip();
                }
            }
            cells.add(cleaned);
        }
        return cells;
    }

    static boolean containsCjk(String text) {
        return text.codePoints().anyMatch(codePoint -> switch (Character.UnicodeScript.of(codePoint)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> true;
            default -> false;
        });
    }

    private static boolean looksLikeAsciiNote(String line) {
        boolean ascii = line.codePoints().allMatch(codePoint -> codePoint < 128);
        return ascii && line.split("\\s+").length <= 8 && line.chars().anyMatch(Character::isLetter);
    }
}
