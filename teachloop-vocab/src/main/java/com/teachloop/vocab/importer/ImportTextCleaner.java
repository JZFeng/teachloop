package com.teachloop.vocab.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 格式清洗（T391 / FR-115 / spec §20）：自动执行，但每一处改动都写进
 * {@link ParseResult.Cleanup}，不静默。
 *
 * <p>负责 token 级改动：全角逗号/分号/顿号转半角、去成对引号、去混入的音标（`/ˈæpl/`、`[..]`、
 * 含 IPA 字符的括号）与词性标注（`n.`、`(vt.)`、`adv.`）。空白与行首字符串由
 * {@link DictionaryTextParser} 负责（它同时知道行号与原始行）。
 */
public final class ImportTextCleaner {

    private static final Pattern PHONETIC = Pattern.compile(
            "^(.*?)\\s*(/[^/]+/|\\[[^\\]]*\\]|\\([^)]*[\u02C8\u02CC\u02D0\u0259\u026A\u028A\u00E6\u0251\u0254\u025C\u03B8\u00F0\u0283\u0292\u014B][^)]*\\))$");
    private static final Pattern POS_TAG = Pattern.compile(
            "^\\(?(?:n|v|adj|adv|prep|pron|conj|num|art|int|vt|vi|phr|abbr|aux|det|modal)\\.\\)?$",
            Pattern.CASE_INSENSITIVE);

    private ImportTextCleaner() {
    }

    public record CleanedLine(List<String> tokens, List<ParseResult.Cleanup> cleanups) {
    }

    public static CleanedLine cleanLine(String line, int lineNumber) {
        List<ParseResult.Cleanup> cleanups = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (String raw : split(line, lineNumber, cleanups)) {
            String cleaned = cleanToken(raw, lineNumber, cleanups);
            if (cleaned != null && !cleaned.isEmpty()) {
                tokens.add(cleaned);
            }
        }
        return new CleanedLine(List.copyOf(tokens), List.copyOf(cleanups));
    }

    /** 清洗一个 token；返回 null 表示整词被丢弃（纯音标或纯词性标注）。 */
    public static String cleanToken(String raw, int lineNumber, List<ParseResult.Cleanup> cleanups) {
        String text = raw.strip();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\u201C' && last == '\u201D')) {
                String inner = text.substring(1, text.length() - 1).strip();
                cleanups.add(new ParseResult.Cleanup(lineNumber, "去引号", text, inner));
                text = inner;
            }
        }
        Matcher phonetic = PHONETIC.matcher(text);
        if (phonetic.matches()) {
            String kept = phonetic.group(1).strip();
            cleanups.add(new ParseResult.Cleanup(lineNumber, "去音标", text, kept));
            text = kept;
        }
        if (!text.isEmpty() && POS_TAG.matcher(text).matches()) {
            cleanups.add(new ParseResult.Cleanup(lineNumber, "去词性标注", text, ""));
            return null;
        }
        return text.isEmpty() ? null : text;
    }

    private static List<String> split(String line, int lineNumber, List<ParseResult.Cleanup> cleanups) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char closingQuote = 0;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (quoted) {
                current.append(ch);
                if (ch == closingQuote) {
                    quoted = false;
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
                closingQuote = '"';
                current.append(ch);
                continue;
            }
            if (ch == '\u201C') {
                quoted = true;
                closingQuote = '\u201D';
                current.append(ch);
                continue;
            }
            if (isFullWidthSeparator(ch)) {
                String halfWidth = switch (ch) {
                    case '\uFF1B' -> ";";
                    default -> ",";
                };
                cleanups.add(new ParseResult.Cleanup(
                        lineNumber, "全角标点转半角", String.valueOf(ch), halfWidth));
                flush(tokens, current);
                continue;
            }
            if (isSeparator(ch)) {
                flush(tokens, current);
                continue;
            }
            current.append(ch);
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
        return ch == ',' || ch == ';' || Character.isWhitespace(ch);
    }

    private static boolean isFullWidthSeparator(char ch) {
        return ch == '\uFF0C' || ch == '\uFF1B' || ch == '\u3001';
    }
}
