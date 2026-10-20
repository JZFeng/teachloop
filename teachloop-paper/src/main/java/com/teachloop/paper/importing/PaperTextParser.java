package com.teachloop.paper.importing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把抽取出来的行文本解析成题项 + 答案（T186）。
 *
 * <p>题目与答案在同一个文档里：题干/选项在前，答案区（「答案」「参考答案」
 * 「Answer(s)」单独一行，或带冒号后跟内容）在后；也支持题干行内的
 * 「（答案：A）」写法。没有答案的题照样产出题项（{@code answerKey == null}），
 * 由调用方列入 {@code missingAnswers}。</p>
 *
 * <p>答案行支持：{@code 1. A}、{@code 1 A}、{@code 1-5 ABCDA}（题号区间）、
 * {@code 1 A 2 B 3 C}（一行多对），答案值可以是多个字母（多选）。</p>
 *
 * <p>题号重复、答案行解析失败、区间答案数量不匹配 → 抛 {@link ImportException}，
 * 消息里带行号。答案是首次出现的题号、重复题号不覆盖。</p>
 */
public final class PaperTextParser {

    /** 一道解析出来的题。{@code sourceLine} 是题号所在行，便于教师定位。 */
    public record ParsedItem(int number, int sourceLine, String stem, String options, String answerKey) {
    }

    /** 解析结果：题项（按文档顺序）、缺答案题号、非致命提醒。 */
    public record Result(List<ParsedItem> items, List<Integer> missingAnswers, List<String> warnings) {
    }

    private static final Pattern NUMBER_LINE = Pattern.compile("^\\s*(\\d+)\\s*[.、．)）]\\s*(.*)$");
    private static final Pattern OPTION_LINE = Pattern.compile("^\\s*([A-Ga-g])\\s*[.、．)）]\\s*(.*)$");

    /** 单独的「答案」「参考答案」「Answer(s)」行，冒号可有可无。 */
    private static final Pattern CHINESE_ANSWER_MARKER =
            Pattern.compile("^\\s*(?:参考答案|答案)\\s*[:：]?\\s*$");
    private static final Pattern ENGLISH_ANSWER_MARKER =
            Pattern.compile("^\\s*(?:answers?|answer)\\s*[:：]?\\s*$", Pattern.CASE_INSENSITIVE);
    /** 「答案：1 A 2 B」这类标记与内容同行的写法。 */
    private static final Pattern CHINESE_ANSWER_START =
            Pattern.compile("^\\s*(?:参考答案|答案)\\s*[:：]?\\s*(.*)$");
    private static final Pattern ENGLISH_ANSWER_START =
            Pattern.compile("^\\s*(?:answers?|answer)\\s*[:：]\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern SECTION_HEADER = Pattern.compile(
            "^\\s*(?:第[一二三四五六七八九十百]+\\s*(?:部分|节|大题)|[一二三四五六七八九十]+\\s*[、.]|Part\\s+[IVXLC\\d]+\\b).*$",
            Pattern.CASE_INSENSITIVE);

    /** 一行里的「题号 + 答案」对；题号区间写成 36-40；答案可以是 AB 这种多选。 */
    private static final Pattern ANSWER_PAIR = Pattern.compile(
            "(\\d+)\\s*(?:[-–—~～至]\\s*(\\d+))?\\s*[.、．)）:：]?\\s*"
                    + "([A-Ga-g]+(?:\\s*[,，、/]\\s*[A-Ga-g]+)*)");

    /** 题干行内的「（答案：A）」。 */
    private static final Pattern INLINE_ANSWER = Pattern.compile(
            "[（(]\\s*答案\\s*[:：]?\\s*([A-Ga-g]+(?:\\s*[,，、/]\\s*[A-Ga-g]+)*)\\s*[)）]");

    private PaperTextParser() {
    }

    public static Result parse(List<DocumentTextExtractor.SourceLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ImportException("文档是空的，没有可导入的内容");
        }
        int answerStart = findAnswerZoneStart(lines);

        List<Question> questions = new ArrayList<>();
        Map<Integer, Integer> firstQuestionLine = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        parseBody(lines, answerStart, questions, firstQuestionLine, warnings);
        if (questions.isEmpty()) {
            throw new ImportException("没有识别到任何题号；题号行应写成「1. 题目」「2、题目」这种形式");
        }

        Map<Integer, AnswerValue> answers = new LinkedHashMap<>();
        parseAnswerLines(lines, answerStart, answers, warnings);
        applyAnswers(questions, answers, warnings);

        List<ParsedItem> items = new ArrayList<>();
        List<Integer> missingAnswers = new ArrayList<>();
        for (Question q : questions) {
            String answerKey = q.answerKey;
            if (answerKey == null || answerKey.isBlank()) {
                missingAnswers.add(q.number);
            }
            items.add(new ParsedItem(q.number, q.sourceLine, q.stemText(), q.optionsText(), answerKey));
        }
        return new Result(items, missingAnswers, warnings);
    }

    // ------------------------------------------------------------- 答案区定位

    /** 返回答案区第一行的下标；没有答案区时返回 lines.size()。 */
    private static int findAnswerZoneStart(List<DocumentTextExtractor.SourceLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String text = strip(lines.get(i).text());
            if (CHINESE_ANSWER_MARKER.matcher(text).matches()
                    || ENGLISH_ANSWER_MARKER.matcher(text).matches()) {
                return i + 1;
            }
            Matcher chinese = CHINESE_ANSWER_START.matcher(text);
            if (chinese.matches()) {
                String rest = chinese.group(1);
                return rest == null || rest.isBlank() ? i + 1 : i;
            }
            if (ENGLISH_ANSWER_START.matcher(text).matches()) {
                return i;
            }
        }
        return lines.size();
    }

    // ---------------------------------------------------------------- 题目区

    private static void parseBody(List<DocumentTextExtractor.SourceLine> lines, int answerStart,
                                  List<Question> questions, Map<Integer, Integer> firstQuestionLine,
                                  List<String> warnings) {
        Question current = null;
        for (int i = 0; i < answerStart; i++) {
            DocumentTextExtractor.SourceLine line = lines.get(i);
            String text = strip(line.text());
            if (text.isEmpty()) {
                continue;
            }
            if (SECTION_HEADER.matcher(text).matches()) {
                current = null;
                continue;
            }
            Matcher numberMatcher = NUMBER_LINE.matcher(text);
            if (numberMatcher.matches()) {
                int number = parseNumber(numberMatcher.group(1), line.number());
                Integer previous = firstQuestionLine.get(number);
                if (previous != null) {
                    throw ImportException.atLine(line.number(),
                            "题号 " + number + " 重复（首次出现在第 " + previous + " 行）");
                }
                firstQuestionLine.put(number, line.number());
                current = new Question(number, line.number());
                current.stem.append(numberMatcher.group(2).strip());
                questions.add(current);
                continue;
            }
            Matcher optionMatcher = OPTION_LINE.matcher(text);
            if (optionMatcher.matches()) {
                char letter = Character.toUpperCase(optionMatcher.group(1).charAt(0));
                String content = optionMatcher.group(2).strip();
                if (current == null) {
                    warnings.add("第 " + line.number() + " 行：选项 " + letter + " 前面没有题号，已忽略");
                    continue;
                }
                if (current.options.containsKey(letter)) {
                    warnings.add("第 " + line.number() + " 行：题号 " + current.number + " 的选项 "
                            + letter + " 重复，内容已合并");
                    current.options.get(letter).append('\n').append(content);
                } else {
                    current.options.put(letter, new StringBuilder(content));
                }
                continue;
            }
            // 跨行的题干或很长的选项：追加到当前题的最后一段
            if (current != null) {
                current.appendContinuation(text);
            }
        }
    }

    private static int parseNumber(String raw, int line) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw ImportException.atLine(line, "题号不是有效整数：" + raw);
        }
    }

    // ---------------------------------------------------------------- 答案区

    private static void parseAnswerLines(List<DocumentTextExtractor.SourceLine> lines, int answerStart,
                                         Map<Integer, AnswerValue> answers, List<String> warnings) {
        for (int i = answerStart; i < lines.size(); i++) {
            DocumentTextExtractor.SourceLine line = lines.get(i);
            String text = strip(line.text());
            if (text.isEmpty()) {
                continue;
            }
            text = stripAnswerPrefix(text);
            if (text.isEmpty()) {
                continue;
            }
            Matcher matcher = ANSWER_PAIR.matcher(text);
            int last = 0;
            int matched = 0;
            while (matcher.find()) {
                matched++;
                String gap = text.substring(last, matcher.start());
                if (containsDigit(gap)) {
                    throw ImportException.atLine(line.number(),
                            "无法解析的答案内容「" + gap.strip() + "」（支持 1. A / 1 A / 1-5 ABCDA / 1 A 2 B 3 C）");
                }
                last = matcher.end();
                String letters = normalizeLetters(matcher.group(3));
                if (matcher.group(2) != null) {
                    int from = parseNumber(matcher.group(1), line.number());
                    int to = parseNumber(matcher.group(2), line.number());
                    if (to < from) {
                        throw ImportException.atLine(line.number(), "题号区间 " + from + "-" + to + " 不合法");
                    }
                    int expected = to - from + 1;
                    if (letters.length() != expected) {
                        throw ImportException.atLine(line.number(), "题号区间 " + from + "-" + to
                                + " 需要 " + expected + " 个答案，实际 " + letters.length() + " 个（" + letters + "）");
                    }
                    for (int k = 0; k < expected; k++) {
                        putAnswer(answers, warnings, from + k, String.valueOf(letters.charAt(k)), line.number());
                    }
                } else {
                    putAnswer(answers, warnings, parseNumber(matcher.group(1), line.number()), letters,
                            line.number());
                }
            }
            String tail = text.substring(last);
            if (containsDigit(tail)) {
                throw ImportException.atLine(line.number(),
                        "无法解析的答案内容「" + tail.strip() + "」（支持 1. A / 1 A / 1-5 ABCDA / 1 A 2 B 3 C）");
            }
            if (matched == 0) {
                if (containsDigit(text)) {
                    throw ImportException.atLine(line.number(),
                            "无法解析的答案行「" + text + "」（支持 1. A / 1 A / 1-5 ABCDA / 1 A 2 B 3 C）");
                }
                warnings.add("第 " + line.number() + " 行不是答案行，已忽略：" + text);
            }
        }
    }

    private static void putAnswer(Map<Integer, AnswerValue> answers, List<String> warnings,
                                  int number, String letters, int line) {
        AnswerValue previous = answers.get(number);
        if (previous == null) {
            answers.put(number, new AnswerValue(letters, line));
            return;
        }
        if (previous.value.equals(letters)) {
            warnings.add("第 " + line + " 行：题号 " + number + " 的答案重复给出（同为 "
                    + letters + "），已按第一次为准");
            return;
        }
        throw ImportException.atLine(line, "题号 " + number + " 的答案重复且不一致（第 "
                + previous.line + " 行给的是 " + previous.value + "，此处是 " + letters + "）");
    }

    private static void applyAnswers(List<Question> questions, Map<Integer, AnswerValue> answers,
                                     List<String> warnings) {
        // 先抽题干内答案并把它从题干里删掉，再与答案区合并；题干内答案优先。
        for (Question q : questions) {
            q.extractInlineAnswer();
        }
        Map<Integer, Question> byNumber = new LinkedHashMap<>();
        for (Question q : questions) {
            byNumber.put(q.number, q);
        }
        for (Map.Entry<Integer, AnswerValue> entry : answers.entrySet()) {
            Question q = byNumber.get(entry.getKey());
            if (q == null) {
                warnings.add("第 " + entry.getValue().line + " 行：答案题号 " + entry.getKey()
                        + " 没有对应题目，已忽略");
                continue;
            }
            if (q.answerKey != null && !q.answerKey.equals(entry.getValue().value)) {
                warnings.add("题号 " + q.number + " 的题干内答案 " + q.answerKey + " 与答案区 "
                        + entry.getValue().value + " 不一致，按题干内答案为准");
                continue;
            }
            q.answerKey = entry.getValue().value;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static String strip(String raw) {
        return raw == null ? "" : raw.strip();
    }

    private static String stripAnswerPrefix(String text) {
        if (CHINESE_ANSWER_MARKER.matcher(text).matches()
                || ENGLISH_ANSWER_MARKER.matcher(text).matches()) {
            return "";
        }
        Matcher chinese = CHINESE_ANSWER_START.matcher(text);
        if (chinese.matches()) {
            return chinese.group(1) == null ? "" : chinese.group(1).strip();
        }
        Matcher english = ENGLISH_ANSWER_START.matcher(text);
        if (english.matches()) {
            return english.group(1) == null ? "" : english.group(1).strip();
        }
        return text;
    }

    private static boolean containsDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeLetters(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toUpperCase(raw.charAt(i));
            if (c >= 'A' && c <= 'G') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private record AnswerValue(String value, int line) {
    }

    /** 解析过程中的可变题目，最后转成 {@link ParsedItem}。 */
    private static final class Question {
        private final int number;
        private final int sourceLine;
        private final StringBuilder stem = new StringBuilder();
        private final Map<Character, StringBuilder> options = new LinkedHashMap<>();
        private String answerKey;

        private Question(int number, int sourceLine) {
            this.number = number;
            this.sourceLine = sourceLine;
        }

        private void appendContinuation(String text) {
            if (options.isEmpty()) {
                if (stem.length() > 0) {
                    stem.append('\n');
                }
                stem.append(text);
                return;
            }
            Map.Entry<Character, StringBuilder> last =
                    options.entrySet().stream().reduce((a, b) -> b).orElseThrow();
            if (last.getValue().length() > 0) {
                last.getValue().append('\n');
            }
            last.getValue().append(text);
        }

        /** 从题干里抽出「（答案：X）」并从题干文本中删掉。 */
        private void extractInlineAnswer() {
            String stemText = stem.toString();
            Matcher matcher = INLINE_ANSWER.matcher(stemText);
            StringJoiner answers = new StringJoiner("");
            StringBuilder cleaned = new StringBuilder();
            int last = 0;
            while (matcher.find()) {
                String value = normalizeLetters(matcher.group(1));
                if (!value.isEmpty()) {
                    answers.add(value);
                    cleaned.append(stemText, last, matcher.start());
                    last = matcher.end();
                }
            }
            if (last == 0) {
                return;
            }
            cleaned.append(stemText.substring(last));
            stem.setLength(0);
            stem.append(cleaned.toString().strip());
            if (!answers.toString().isEmpty()) {
                answerKey = answers.toString();
            }
        }

        private String stemText() {
            String text = stem.toString().strip();
            return text.isEmpty() ? null : text;
        }

        private String optionsText() {
            if (options.isEmpty()) {
                return null;
            }
            StringJoiner joiner = new StringJoiner("\n");
            for (Map.Entry<Character, StringBuilder> entry : options.entrySet()) {
                String content = entry.getValue().toString().strip();
                joiner.add(entry.getKey() + ". " + content);
            }
            return joiner.toString();
        }
    }

}
