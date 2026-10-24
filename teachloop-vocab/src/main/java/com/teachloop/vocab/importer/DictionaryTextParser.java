package com.teachloop.vocab.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 词表文本解析器（T390 / FR-108 / spec §15）：CSV、MD、TXT 与「粘贴加词」共用同一个入口。
 *
 * <p>行类型按 spec §15.1 处理：空行跳过；`#` 标题与 `>` 引用当说明文字跳过；
 * ``` 围栏切换代码块（块内按普通词行）；表格行按单元格切、分隔行跳过、首个非分隔行当表头；
 * 列表标记去掉后按普通词行；普通行再按「逗号（中英文）/ 分号 / 空白」切分，
 * 忽略空 token 与行尾分隔符，双引号或中文引号内的空格保留（`"take off"`）。
 *
 * <p>说明文字规则（spec §15.2）：语言 {@code en} 时整行含中日韩字符即跳过；
 * 语言 {@code zh} 时纯 ASCII 短行不擅自丢弃，标为 {@link ParseResult.PendingLine} 交预览页确认。
 * 所有跳过都在结果里带行号与原文，不静默。
 *
 * <p>列映射（有表头时哪一列是正面/释义）不在这里做：那是预览与入库管线（T391/T393）的职责。
 */
@Component
public class DictionaryTextParser {

    public static final String LANGUAGE_EN = "en";
    public static final String LANGUAGE_ZH = "zh";

    private static final Pattern LIST_MARKER = Pattern.compile("^(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile("^:?-{3,}:?$");

    public ParseResult parse(String text) {
        return parse(text, LANGUAGE_EN);
    }

    public ParseResult parse(String text, String language) {
        String lang = language == null ? LANGUAGE_EN : language.strip().toLowerCase(Locale.ROOT);
        List<ParseResult.ParsedToken> tokens = new ArrayList<>();
        List<ParseResult.SkippedLine> skipped = new ArrayList<>();
        List<ParseResult.PendingLine> pending = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return new ParseResult(0, tokens, skipped, pending);
        }

        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int lineCount = lines.length;
        if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
            lineCount--; // 末尾换行不算一行
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
                    parseWordLine(lineNumber, trimmed, lang, tokens, skipped, pending);
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
                    if (!cell.isEmpty()) {
                        tokens.add(new ParseResult.ParsedToken(cell, lineNumber));
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
            String content = trimmed;
            var marker = LIST_MARKER.matcher(content);
            if (marker.find()) {
                content = content.substring(marker.end()).strip();
            }
            if (content.isEmpty()) {
                skipped.add(new ParseResult.SkippedLine(lineNumber, raw, "只有列表标记，没有单词"));
                continue;
            }
            parseWordLine(lineNumber, content, lang, tokens, skipped, pending);
        }
        return new ParseResult(lineCount, tokens, skipped, pending);
    }

    private void parseWordLine(int lineNumber, String content, String language,
                               List<ParseResult.ParsedToken> tokens,
                               List<ParseResult.SkippedLine> skipped,
                               List<ParseResult.PendingLine> pending) {
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
        List<String> words = tokenize(content);
        if (words.isEmpty()) {
            skipped.add(new ParseResult.SkippedLine(lineNumber, content, "没有解析出单词"));
            return;
        }
        for (String word : words) {
            tokens.add(new ParseResult.ParsedToken(word, lineNumber));
        }
    }

    /** 按「逗号（中英文）/ 分号 / 顿号 / 空白」切；引号内空格保留。 */
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char closingQuote = 0;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (quoted) {
                if (ch == closingQuote) {
                    quoted = false;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
                closingQuote = '"';
            } else if (ch == '\u201C') {
                quoted = true;
                closingQuote = '\u201D';
            } else if (isSeparator(ch)) {
                flush(tokens, current);
            } else {
                current.append(ch);
            }
        }
        flush(tokens, current);
        return tokens;
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        String token = current.toString().strip();
        if (!token.isEmpty()) {
            tokens.add(token);
        }
        current.setLength(0);
    }

    private static boolean isSeparator(char ch) {
        return ch == ',' || ch == '\uFF0C' || ch == ';' || ch == '\uFF1B' || ch == '\u3001'
                || Character.isWhitespace(ch);
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
