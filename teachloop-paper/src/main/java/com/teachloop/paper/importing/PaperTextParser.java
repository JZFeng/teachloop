package com.teachloop.paper.importing;

import com.teachloop.paper.domain.QuestionType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把抽取出来的行文本解析成「部分 → 题项 + 答案」（T186 / T217）。
 *
 * <p>题目与答案在同一个文档里：题干/选项在前，答案区（「答案」「参考答案」
 * 「Answer(s)」单独一行，或带冒号后跟内容）在后；也支持题干行内的
 * 「（答案：A）」写法。没有答案的题照样产出题项（{@code answerKey == null}），
 * 由调用方列入 {@code missingAnswers}。</p>
 *
 * <p>结构识别（T217）：{@code 第X部分} 切出 {@link ParsedSection}，
 * {@code 第X节} 只用来取「每小题 X 分」和「共 N 小题」，题项分值逐题带着走，
 * 所以同一部分里小节分值不同（完形 1 分、语法填空 1.5 分）也能算对总分。
 * 主观题部分（写作/作文/应用文/口语）不产题项，只给一条提醒。</p>
 *
 * <p>标题里的三个数字自检（T454）：能同时读到「共 N 题」「每题 X 分」「满分 Y 分」时，
 * 校验 {@code N × X == Y}；对不上只进 {@code warnings}，不改分值、不阻断导入。</p>
 *
 * <p>材料（T231）：完形填空、七选五、语法填空的短文没有独立字段，
 * 因此把材料并入题干开头，并把「第 k 空」标成 {@code ___(题号)___}，
 * 让每道题单独拿出来也能作答。</p>
 *
 * <p>答案行支持：{@code 1. A}、{@code 1 A}、{@code 1-5 ABCDA}（题号区间）、
 * {@code 1 A 2 B 3 C}（一行多对），答案值可以是多个字母（多选）。</p>
 *
 * <p>题号重复、答案行解析失败、区间答案数量不匹配 → 抛 {@link ImportException}，
 * 消息里带行号。答案是首次出现的题号、重复题号不覆盖。</p>
 */
public final class PaperTextParser {

    /**
     * 一道解析出来的题。{@code sourceLine} 是题号所在行，便于教师定位。
     *
     * <p>{@code questionType} 只对写作题有值（{@link QuestionType#ESSAY}）；客观题保持 null，
     * 由调用方按「有没有选项」推断。{@code rubricMarkdown} / {@code referenceMarkdown}
     * 是题项级评分标准与参考范文（D-24 / D-25），只有写作题会填。</p>
     */
    public record ParsedItem(int number, int sourceLine, String stem, String options, String answerKey,
                             BigDecimal points, QuestionType questionType, String rubricMarkdown,
                             String referenceMarkdown) {

        /** 兼容既有调用：客观题，没有题项级 rubric/范文。 */
        public ParsedItem(int number, int sourceLine, String stem, String options, String answerKey,
                          BigDecimal points) {
            this(number, sourceLine, stem, options, answerKey, points, null, null, null);
        }
    }

    /** 一个试卷部分（{@code 第X部分}）；没有部分标题的文档只有一条、标题为空。 */
    public record ParsedSection(String title, BigDecimal pointsPerItem, List<ParsedItem> items) {
    }

    /** 解析结果：部分（按文档顺序）、缺答案题号、非致命提醒。 */
    public record Result(List<ParsedSection> sections, List<Integer> missingAnswers, List<String> warnings) {

        /** 所有题项，按文档顺序拉平。 */
        public List<ParsedItem> items() {
            List<ParsedItem> all = new ArrayList<>();
            for (ParsedSection section : sections) {
                all.addAll(section.items());
            }
            return all;
        }
    }

    private static final Pattern NUMBER_LINE = Pattern.compile("^(\\d+)\\s*[.、．)）]\\s*(.*)$");
    private static final Pattern OPTION_LINE = Pattern.compile("^([A-G])\\s*[.、．)）]\\s*(.*)$");
    /** 题干行内并排的选项：{@code 41. A. old  B. famous  C. different  D. fresh}。 */
    private static final Pattern INLINE_OPTION = Pattern.compile("(?:^|\\s)([A-G])\\s*[.、．)）]\\s*");

    /** 答案标记词：参考答案 / 标准答案 / 正确答案 / 答案（老师怎么写都认）。 */
    private static final String ANSWER_WORDS = "(?:参考答案|标准答案|正确答案|答案)";

    /** 单独的「答案」「参考答案」「Answer(s)」行，冒号可有可无。 */
    private static final Pattern CHINESE_ANSWER_MARKER =
            Pattern.compile("^\\s*" + ANSWER_WORDS + "\\s*[:：]?\\s*$");
    private static final Pattern ENGLISH_ANSWER_MARKER =
            Pattern.compile("^\\s*(?:answers?|answer)\\s*[:：]?\\s*$", Pattern.CASE_INSENSITIVE);
    /** 「答案：1 A 2 B」这类标记与内容同行的写法。 */
    private static final Pattern CHINESE_ANSWER_START =
            Pattern.compile("^\\s*" + ANSWER_WORDS + "\\s*[:：]?\\s*(.*)$");
    private static final Pattern ENGLISH_ANSWER_START =
            Pattern.compile("^\\s*(?:answers?|answer)\\s*[:：]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    /**
     * 带前缀的答案区标题，如 {@code 试卷参考答案与解析 (卷末附录)}。
     *
     * <p>只认「参考答案 / 标准答案 / 正确答案」这三个双字词（几乎只出现在答案区），
     * 单个「答案」不放宽——正文里的「请将答案写在…」不能被当成答案区。长度与句末标点是
     * 第二道闸：标题短、没有句号。</p>
     */
    private static final Pattern ANSWER_HEADING_HINT = Pattern.compile("(?:参考答案|标准答案|正确答案)");

    /** 一部分：{@code 第一部分 听力（共两节，满分 30 分）}、{@code Part II Reading}。 */
    private static final Pattern PART_HEADER = Pattern.compile(
            "^第\\s*(?:[一二三四五六七八九十百]+|\\d+)\\s*部分\\s*(.*)$");
    /** 一小节：{@code 第一节（共 5 小题；每小题 1.5 分，满分 7.5 分）}。 */
    private static final Pattern SUB_HEADER = Pattern.compile(
            "^第\\s*(?:[一二三四五六七八九十百]+|\\d+)\\s*节\\s*(.*)$");
    /** 没有「第X部分」时的中文大标题：{@code 一、单项选择题}。 */
    private static final Pattern CHINESE_SECTION = Pattern.compile("^[一二三四五六七八九十]+\\s*[、.]\\s*(.*)$");
    private static final Pattern ENGLISH_SECTION = Pattern.compile("^Part\\s+[IVXLC\\d]+\\b.*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 数字段：阿拉伯数字（可带小数点），或中文数字（{@code 一}、{@code 两}、{@code 十五}、
     * {@code 二十五}、{@code 一百零五}、{@code 一点五}）。
     *
     * <p>T455（2026-10-25 用户要求）：老师写「每小题一分」与「每小题 1 分」必须等价。</p>
     */
    private static final String NUMBER = "(?:\\d+(?:\\.\\d+)?"
            + "|[零一二两三四五六七八九十百]+(?:点[零一二三四五六七八九]+)?)";
    /** 分值单位：分 / point(s) / pt(s)；{@code 分(?!钟)} 防止「每小题 5 分钟」被读成 5 分。 */
    private static final String SCORE_UNIT = "(?:分(?!钟)|points?|pts?)";
    /** 中文数字逐位表（「两」按 2 读）。 */
    private static final Map<Character, Integer> CHINESE_DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('一', 1), Map.entry('二', 2), Map.entry('两', 2),
            Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6),
            Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9));

    /**
     * 「每小题 1.5 分」的各种写法：{@code 每小题 1.5 分} / {@code 每题 2 分} /
     * {@code 每小题 1.5 points} / {@code 1.5 points each} / {@code 1.5/题} /
     * {@code 1.5 / quiz /question} / {@code each （question）1.5} / {@code each 1.5 points} /
     * {@code 每小题一分}。
     *
     * <p>D-27：中文老师出英文卷时两种写法混用，分值语法统一到 {@code 分|points?|pts?}。
     * T454 按教师实测再放宽两类：斜杠式（{@code 1.5/题}、{@code 1.5 / quiz /question}），
     * 以及题数词在前、分数在后的英文式（{@code each （question）1.5}）。</p>
     *
     * <p>「每(小)题」分支仍要求单位：{@code 每小题 5 秒钟} 不能被读成 5 分。</p>
     */
    private static final Pattern POINTS_PER_ITEM = Pattern.compile(
            // 每(小)题 1.5 分 / 每题一分
            "每(?:小)?题\\s*[:：]?\\s*(" + NUMBER + ")\\s*" + SCORE_UNIT
                    // 1.5 分 每题 / 1.5 points each
                    + "|(" + NUMBER + ")\\s*" + SCORE_UNIT + "\\s*(?:每(?:小)?题|each)"
                    // 1.5/题、1.5 分/题、1.5 / quiz /question
                    + "|(" + NUMBER + ")\\s*(?:" + SCORE_UNIT + ")?\\s*/\\s*"
                    + "(?:小题|题|questions?|quiz(?:zes|s)?|items?)"
                    // each （question）1.5 / each 1.5 points
                    + "|each\\s*[（(]?\\s*(?:questions?|quiz(?:zes|s)?|items?)?\\s*[)）]?\\s*"
                    + "[:：]?\\s*(" + NUMBER + ")",
            Pattern.CASE_INSENSITIVE);
    /**
     * 标题里的题数：{@code 共 5 小题} / {@code 共5题} / {@code total 5 questions} / {@code total 5 quizs}。
     *
     * <p>只用于「题数 × 每题分值 = 满分」的自检（T454），不改
     * {@link #SUB_QUESTION_COUNT} 那条题数核对的语义。</p>
     */
    private static final Pattern ITEM_COUNT_IN_TITLE = Pattern.compile(
            "共\\s*(" + NUMBER + ")\\s*(?:小题|题)"
                    + "|(?:total|共)\\s*(" + NUMBER + ")\\s*(?:questions?|quiz(?:zes|s)?|items?)",
            Pattern.CASE_INSENSITIVE);
    /**
     * 标题里的满分：{@code 满分 7.5 分} / {@code 满分7.5分} / {@code total score： 7.5} /
     * {@code total： 7.5 points}。
     *
     * <p>英文要求 {@code total score} 或 {@code total:} 带冒号：{@code total 5 questions}
     * 里的 5 是题数，不能读成满分。</p>
     */
    private static final Pattern TOTAL_SCORE_IN_TITLE = Pattern.compile(
            "(?:满分|总分)\\s*[:：]?\\s*(" + NUMBER + ")\\s*(?:" + SCORE_UNIT + ")?"
                    + "|total\\s*score\\s*[:：]?\\s*(" + NUMBER + ")\\s*(?:" + SCORE_UNIT + ")?"
                    + "|total\\s*[:：]\\s*(" + NUMBER + ")\\s*(?:" + SCORE_UNIT + ")?",
            Pattern.CASE_INSENSITIVE);
    /** 任何带单位的分值：{@code 15 分} / {@code 15 points} / {@code 15 pts} / {@code 十五分}。 */
    private static final Pattern ANY_POINTS = Pattern.compile(
            "(" + NUMBER + ")\\s*" + SCORE_UNIT, Pattern.CASE_INSENSITIVE);
    /** 写作任务行的「满分 N 分」：{@code 满分 15 分} / {@code 总分: 15 points} / {@code 满分十五分}。 */
    private static final Pattern TASK_SCORE = Pattern.compile(
            "(?:满分|总分|total)\\s*[:：]?\\s*(" + NUMBER + ")\\s*" + SCORE_UNIT,
            Pattern.CASE_INSENSITIVE);
    /** 写作任务行的人才有的词：用来把「15. 应用文写作」和「1. 表达理解；」区分开。 */
    private static final Pattern WRITING_TASK_HINT = Pattern.compile(
            "写作|作文|应用文|书面表达|口语|演讲|发言稿|书信|通知");
    /** 只有下划线的作答行：不进题干。 */
    private static final Pattern BLANK_ANSWER_LINE = Pattern.compile("^\\s*_{3,}\\s*$");
    /** {@code 共 5 小题} / {@code 共五小题}。 */
    private static final Pattern SUB_QUESTION_COUNT = Pattern.compile(
            "共\\s*(" + NUMBER + ")\\s*小题");
    /** 主观题小节：不产题项。 */
    private static final Pattern SUBJECTIVE = Pattern.compile(
            "写作|作文|应用文|书面表达|口语|翻译|简答|论述|书面");
    /** 大题说明（不是材料，也不是题干）。 */
    private static final Pattern INSTRUCTION = Pattern.compile(
            "^(?:听下面|听第五段|听第[一二三四五六七八九十\\d]+段|听每段"
                    + "|阅读下面|阅读下列|阅读材料"
                    + "|在句子空白处|从每题所给|从短文后|请将答案|将答案写|选出可以|选出最佳)");
    /** 分值说明行里允许的间隔字符：空白、括号与各种连接标点。 */
    private static final String SCORE_SEP = "[\\s（()）【】\\[\\]、，,；;：:./|·—\\-]*";
    /**
     * 只由分值/题数词与连接标点组成的整行：{@code 每小题 1.5 分}、{@code 共 5 小题；每小题 1.5 分，满分 7.5 分}。
     *
     * <p>T456：这种排版说明行既不进材料也不进题干（正文里单独一行时尤其明显）。
     * 普通英文句子（{@code They like each other}）里有词不在白名单，匹配不上，因此不会被误删。</p>
     */
    private static final Pattern SCORE_LINE = Pattern.compile(
            "^" + SCORE_SEP + "(?:(?:共|每(?:小)?题|满分|总分|total|score|each|and|各|小题|题|"
                    + "questions?|quiz(?:zes|s)?|items?|points?|pts?|分|" + NUMBER + ")"
                    + SCORE_SEP + ")+$",
            Pattern.CASE_INSENSITIVE);
    /**
     * 一个空。
     *
     * <p>裸空是 3 个以上下划线；卷面把题号写进空里时，下划线可能绕到题号两边
     * （{@code ______(7)___}、{@code __(7)__}、{@code (7)______}），这几段要当成**同一个空**，
     * 否则一个空会被数成两个、凭空多出一道题（2026-10-22 用户实测：
     * {@code ______(7)} 正常，{@code ______(7)___} 会多出一道）。</p>
     *
     * <p>括号里只认 1–3 位数字，{@code ______ (2019)} 这种正文里的年份不会被并进空。
     * 三个分支的捕获组 1 / 2 是卷面写的题号（裸空没有题号）。</p>
     */
    private static final Pattern BLANK = Pattern.compile(
            // 空 + 括号题号 + 紧贴的下划线尾巴：______(7)___ / __(7)__
            "_{1,}[ \\t]*[(（][ \\t]*(\\d{1,3})[ \\t]*[)）]_{0,}"
                    // 括号题号 + 空：(7)______
                    + "|[(（][ \\t]*(\\d{1,3})[ \\t]*[)）][ \\t]*_{3,}"
                    // 裸空：______
                    + "|_{3,}");
    /**
     * 一行的「题号 + 答案」对；题号区间写成 {@code 36-40}；答案可以是 {@code AB} 这种多选。
     *
     * <p>字母两侧都有「不是字母」的边界：否则 {@code 56. challenging} 会把开头的 c 当成答案 C，
     * 填空题的答案就被静默改成了字母。区间内的答案允许用空格/逗号分隔成
     * {@code 1-5: A B B C C}。</p>
     */
    private static final Pattern ANSWER_PAIR = Pattern.compile(
            "(\\d+)\\s*(?:[-–—~～至]\\s*(\\d+))?\\s*[.、．)）:：]?\\s*"
                    + "(?<![A-Za-z])([A-Ga-g]+(?:[\\s,，、/]+[A-Ga-g]+)*)(?![A-Za-z])");
    /** 题干行内的「（答案：A）」。 */
    private static final Pattern INLINE_ANSWER = Pattern.compile(
            "[（(【]\\s*答案\\s*[:：]?\\s*"
                    + "(?<![A-Za-z])([A-Ga-g]+(?:[\\s,，、/]+[A-Ga-g]+)*)(?![A-Za-z])\\s*[)）】]");

    /** 纯字母串：「ABCDA」「A B C D A」，答题卡形态；只能按题号连续配对，否则拒绝。 */
    private static final Pattern LETTER_SEQUENCE = Pattern.compile("^[A-Ga-g](?:[\\s,，、/]*[A-Ga-g])*$");

    private PaperTextParser() {
    }

    public static Result parse(List<DocumentTextExtractor.SourceLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ImportException("文档是空的，没有可导入的内容");
        }
        int answerStart = findAnswerZoneStart(lines);
        List<String> warnings = new ArrayList<>();
        Context ctx = new Context();
        // 先记下卷面自带题号的题：七选五这类「材料里若干空、却只有部分空是题」的卷子，
        // 填空题按空顺推题号会一路吃掉后面真正的题（2026-10-22：七选五 5 个空吃掉了
        // 完形的 9/10 与语法填空的 11，答案区「11. challenging」因此报无法解析）。
        for (int i = 0; i < answerStart; i++) {
            Matcher numbered = NUMBER_LINE.matcher(strip(lines.get(i).text()));
            if (numbered.matches()) {
                ctx.explicitNumbers.add(parseNumber(numbered.group(1), lines.get(i).number()));
            }
        }

        List<ParsedSection> sections = new ArrayList<>();
        for (Block block : group(lines.subList(0, answerStart), warnings)) {
            if (SUBJECTIVE.matcher(block.title).find()) {
                // D-23：写作/口语部分建 ESSAY 题项（有节按节一题、否则按带题号的写作任务行一题）
                List<ParsedItem> writing = interpretWriting(block, ctx, warnings);
                sections.add(new ParsedSection(block.title, block.hint(), List.copyOf(writing)));
                continue;
            }
            List<ParsedItem> items = new ArrayList<>();
            for (Sub sub : block.subs) {
                items.addAll(interpret(sub, block, ctx, warnings));
            }
            if (!items.isEmpty() || !block.title.isBlank()) {
                sections.add(new ParsedSection(block.title, block.hint(), List.copyOf(items)));
            }
        }

        if (ctx.items.isEmpty()) {
            throw new ImportException("没有识别到任何题号；题号行应写成「1. 题目」「2、题目」这种形式");
        }

        Map<Integer, AnswerValue> answers = new LinkedHashMap<>();
        // 写作题没有「字母答案」，不能混进纯字母串配对里；它的答案区内容是参考范文（D-25）
        Set<Integer> essayNumbers = new HashSet<>();
        List<Integer> questionNumbers = new ArrayList<>();
        for (ParsedItem item : ctx.items) {
            if (item.questionType() == QuestionType.ESSAY) {
                essayNumbers.add(item.number());
            } else {
                questionNumbers.add(item.number());
            }
        }
        // 没有选项的题（语法填空、单句填空）答案就是文本，不能按字母解析
        Set<Integer> freeTextNumbers = new HashSet<>();
        for (ParsedItem item : ctx.items) {
            if (item.questionType() == QuestionType.ESSAY) {
                continue;
            }
            if (item.options() == null || item.options().isBlank()) {
                freeTextNumbers.add(item.number());
            }
        }
        Map<Integer, String> references = new LinkedHashMap<>();
        parseAnswerLines(lines, answerStart, answers, warnings, questionNumbers, freeTextNumbers,
                essayNumbers, references);
        applyAnswers(ctx.byNumber, answers, warnings);
        applyReferences(ctx.byNumber, references);

        List<ParsedItem> finalized = new ArrayList<>(ctx.items.size());
        List<Integer> missingAnswers = new ArrayList<>();
        for (ParsedItem created : ctx.items) {
            ParsedItem merged = ctx.byNumber.getOrDefault(created.number(), created);
            String answerKey = merged.answerKey();
            // points 为 null 表示文档没写「每小题 X 分」，交给调用方按部分分值兜底
            finalized.add(new ParsedItem(merged.number(), merged.sourceLine(), merged.stem(),
                    merged.options(), answerKey, merged.points(), merged.questionType(),
                    merged.rubricMarkdown(), merged.referenceMarkdown()));
            // 写作题由 rubric 判分，没有「缺标准答案」这回事
            if (merged.questionType() != QuestionType.ESSAY
                    && (answerKey == null || answerKey.isBlank())) {
                missingAnswers.add(merged.number());
            }
        }
        return new Result(rebuild(sections, finalized), missingAnswers, warnings);
    }

    // ------------------------------------------------------------- 分块与识别

    /** 遍历时用的可变上下文：题号去重、当前最大题号、已产出的题项。 */
    private static final class Context {
        private final Map<Integer, Integer> firstLine = new LinkedHashMap<>();
        private final List<ParsedItem> items = new ArrayList<>();
        private final Map<Integer, ParsedItem> byNumber = new LinkedHashMap<>();
        /** 卷面上「自己写了题号」的题（1. / 2、这种），填空分组不能占用它们。 */
        private final Set<Integer> explicitNumbers = new HashSet<>();
        private int lastNumber;
    }

    /** 一个「部分」；{@code subs} 至少一条。 */
    private record Block(String title, List<Sub> subs) {
        /** 小节里写的「每小题 X 分」；都没写则返回 null，由调用方用部分的分值兜底。 */
        BigDecimal hint() {
            for (Sub sub : subs) {
                if (sub.points != null) {
                    return sub.points;
                }
            }
            return null;
        }
    }

    /** 一个小节；{@code points} 为 null 表示标题里没写每小题分值。 */
    private static final class Sub {
        private final String title;
        /** 清洗前的原始标题（含「满分 15 分」这类括号说明），写作题取分值要用。 */
        private final String rawTitle;
        private final BigDecimal points;
        private final Integer expectedCount;
        private final List<DocumentTextExtractor.SourceLine> lines = new ArrayList<>();

        private Sub(String title, String rawTitle, BigDecimal points, Integer expectedCount) {
            this.title = title;
            this.rawTitle = rawTitle;
            this.points = points;
            this.expectedCount = expectedCount;
        }
    }

    /** 按「第X部分 / 第X节」把行分组；没有标题时归到一个匿名部分。 */
    private static List<Block> group(List<DocumentTextExtractor.SourceLine> lines, List<String> warnings) {
        List<Block> blocks = new ArrayList<>();
        List<Sub> subs = null;
        Sub current = null;
        for (DocumentTextExtractor.SourceLine line : lines) {
            String text = strip(line.text());
            if (PART_HEADER.matcher(text).matches() || CHINESE_SECTION.matcher(text).matches()
                    || ENGLISH_SECTION.matcher(text).matches()) {
                checkScoreArithmetic(text, line.number(), warnings);
                subs = new ArrayList<>();
                blocks.add(new Block(cleanTitle(text), subs));
                current = new Sub(cleanTitle(text), text, pointsOf(text), null);
                subs.add(current);
                continue;
            }
            if (SUB_HEADER.matcher(text).matches()) {
                checkScoreArithmetic(text, line.number(), warnings);
                if (subs == null) {
                    subs = new ArrayList<>();
                    blocks.add(new Block("", subs));
                }
                current = new Sub(cleanTitle(text), text, pointsOf(text), countOf(text));
                subs.add(current);
                continue;
            }
            if (subs == null) {
                subs = new ArrayList<>();
                blocks.add(new Block("", subs));
            }
            if (current == null) {
                current = new Sub("", "", null, null);
                subs.add(current);
            }
            current.lines.add(line);
        }
        return blocks;
    }

    private static List<ParsedItem> interpret(Sub sub, Block block, Context ctx, List<String> warnings) {
        BigDecimal points = sub.points != null ? sub.points : block.hint();
        List<DocumentTextExtractor.SourceLine> content = new ArrayList<>();
        for (DocumentTextExtractor.SourceLine line : sub.lines) {
            String text = strip(line.text());
            if (text.isEmpty() || INSTRUCTION.matcher(text).find() || isScoreOnlyLine(text)) {
                continue;
            }
            content.add(new DocumentTextExtractor.SourceLine(line.number(), text));
        }
        if (content.isEmpty()) {
            return List.of();
        }
        boolean numbered = content.stream()
                .anyMatch(line -> NUMBER_LINE.matcher(strip(line.text())).matches());
        List<ParsedItem> items = numbered
                ? interpretNumbered(content, points, ctx, warnings)
                : interpretBlanks(content, sub, points, ctx, warnings);
        if (sub.expectedCount != null && sub.expectedCount != items.size()) {
            warnings.add("「" + sub.title + "」写明共 " + sub.expectedCount + " 小题，实际识别 "
                    + items.size() + " 题，请核对");
        }
        return items;
    }

    // --------------------------------------------------------------- 有题号

    /** 可变的中间题项：材料、选项、题干边解析边补。 */
    private static final class Draft {
        private final int number;
        private final int sourceLine;
        private final StringBuilder stem;
        private final String material;
        private final Map<Character, StringBuilder> options = new LinkedHashMap<>();

        private Draft(int number, int sourceLine, String stem, String material) {
            this.number = number;
            this.sourceLine = sourceLine;
            this.stem = new StringBuilder(stem == null ? "" : stem);
            this.material = material;
        }
    }

    private static List<ParsedItem> interpretNumbered(List<DocumentTextExtractor.SourceLine> content,
                                                      BigDecimal points, Context ctx, List<String> warnings) {
        List<String> material = new ArrayList<>();
        List<Draft> drafts = new ArrayList<>();
        List<DocumentTextExtractor.SourceLine> detachedOptions = new ArrayList<>();
        Draft current = null;
        boolean materialOpen = false;

        for (DocumentTextExtractor.SourceLine line : content) {
            String text = strip(line.text());
            if (isMaterialLabel(text)) {
                // 标签（A/B/C/D…）只用来标记「新材料从这里开始」，本身对考生没有信息量：
                // 它若进入 material，会被 finish() 拼到该篇每道题的题干开头，看起来像标准答案。
                material.clear();
                materialOpen = true;
                current = null;
                continue;
            }
            Matcher number = NUMBER_LINE.matcher(text);
            if (number.matches()) {
                int value = parseNumber(number.group(1), line.number());
                Integer previous = ctx.firstLine.get(value);
                if (previous != null) {
                    throw ImportException.atLine(line.number(),
                            "题号 " + value + " 重复（首次出现在第 " + previous + " 行）");
                }
                ctx.firstLine.put(value, line.number());
                ctx.lastNumber = Math.max(ctx.lastNumber, value);
                String rest = number.group(2).strip();
                Draft draft = new Draft(value, line.number(), "", String.join("\n", material));
                splitInlineOptions(rest, draft);
                drafts.add(draft);
                current = draft;
                materialOpen = false;
                continue;
            }
            Matcher option = OPTION_LINE.matcher(text);
            if (option.matches()) {
                char letter = option.group(1).charAt(0);
                String body = option.group(2).strip();
                if (current == null) {
                    detachedOptions.add(line);
                } else {
                    StringBuilder slot = current.options.get(letter);
                    if (slot == null) {
                        current.options.put(letter, new StringBuilder(body));
                    } else {
                        warnings.add("第 " + line.number() + " 行：题号 " + current.number + " 的选项 "
                                + letter + " 重复，内容已合并");
                        slot.append('\n').append(body);
                    }
                }
                continue;
            }
            // 材料段落 or 续行
            if (materialOpen || current == null) {
                material.add(text);
            } else {
                appendContinuation(current, text);
            }
        }

        if (!detachedOptions.isEmpty()) {
            warnings.add("第 " + detachedOptions.get(0).number() + " 行起有 " + detachedOptions.size()
                    + " 个选项没有对应题号，已忽略");
        }
        return finish(drafts, points, ctx, warnings);
    }

    /** 题干行内并排的选项拆成 stem + options；字母必须从 A 起连续、至少两个。 */
    private static void splitInlineOptions(String rest, Draft draft) {
        Matcher matcher = INLINE_OPTION.matcher(rest);
        List<int[]> marks = new ArrayList<>();
        List<Character> letters = new ArrayList<>();
        while (matcher.find()) {
            char letter = matcher.group(1).charAt(0);
            int expected = 'A' + letters.size();
            if (letter != expected && !letters.isEmpty()) {
                break;
            }
            letters.add(letter);
            marks.add(new int[]{matcher.start(), matcher.end()});
        }
        if (letters.size() < 2) {
            draft.stem.append(rest);
            return;
        }
        draft.stem.append(rest.substring(0, marks.get(0)[0]).strip());
        for (int i = 0; i < letters.size(); i++) {
            int from = marks.get(i)[1];
            int to = i + 1 < letters.size() ? marks.get(i + 1)[0] : rest.length();
            draft.options.put(letters.get(i), new StringBuilder(rest.substring(from, to).strip()));
        }
    }

    private static void appendContinuation(Draft draft, String text) {
        if (draft.options.isEmpty()) {
            if (draft.stem.length() > 0) {
                draft.stem.append('\n');
            }
            draft.stem.append(text);
            return;
        }
        Map.Entry<Character, StringBuilder> last =
                draft.options.entrySet().stream().reduce((a, b) -> b).orElseThrow();
        if (last.getValue().length() > 0) {
            last.getValue().append('\n');
        }
        last.getValue().append(text);
    }

    /**
     * 中间题项 → 最终题项。题干为空的（完形填空那种「41. A. … B. …」）从材料里
     * 取第 k 个空，把空标成 {@code ___(题号)___}；材料并入题干开头。
     */
    private static List<ParsedItem> finish(List<Draft> drafts, BigDecimal points, Context ctx,
                                           List<String> warnings) {
        Map<String, Integer> blankCursor = new LinkedHashMap<>();
        List<ParsedItem> out = new ArrayList<>();
        for (Draft draft : drafts) {
            String material = draft.material == null ? "" : draft.material;
            int blanks = countBlanks(material);
            String stem;
            if (draft.stem.isEmpty() && blanks > 0) {
                int index = blankCursor.merge(material, 1, Integer::sum) - 1;
                if (index < blanks) {
                    stem = labelBlank(material, index, draft.number);
                } else {
                    stem = material;
                    warnings.add("题号 " + draft.number + " 没有题干，材料里的空也不够分，题干留空");
                }
            } else if (material.isBlank()) {
                stem = draft.stem.toString().strip();
            } else {
                stem = material.strip() + "\n\n" + draft.stem.toString().strip();
            }
            String options = optionsText(draft.options);
            out.add(register(new ParsedItem(draft.number, draft.sourceLine, stem.isBlank() ? null : stem,
                    options, null, points), ctx));
        }
        return out;
    }

    // ------------------------------------------------------------- 只有空

    /** 一段材料里一个题号都没有（七选五 / 语法填空）：一个空一题，题号接在上一题后面。 */
    private static List<ParsedItem> interpretBlanks(List<DocumentTextExtractor.SourceLine> content, Sub sub,
                                                    BigDecimal points, Context ctx, List<String> warnings) {
        List<String> material = new ArrayList<>();
        List<DocumentTextExtractor.SourceLine> optionLines = new ArrayList<>();
        for (DocumentTextExtractor.SourceLine line : content) {
            if (OPTION_LINE.matcher(strip(line.text())).matches()) {
                optionLines.add(line);
            } else {
                material.add(strip(line.text()));
            }
        }
        String joined = String.join("\n", material);
        int blanks = countBlanks(joined);
        if (blanks == 0) {
            if (!sub.title.isBlank()) {
                warnings.add("「" + sub.title + "」没有识别到题号，也没有可填空，已跳过");
            }
            return List.of();
        }
        String options = optionLines.size() >= 2 ? optionsText(optionLines) : null;
        if (options == null && optionLines.size() == 1) {
            warnings.add("第 " + optionLines.get(0).number() + " 行：只有一个选项，已忽略");
        }
        List<ParsedItem> out = new ArrayList<>();
        for (int i = 0; i < blanks; i++) {
            // 卷面把题号写进了空里（______(7)___）就以卷面为准：老师手写的编号才对应答案区，
            // 顺推编号只用于没写题号的空（2026-10-22 用户实测）。
            Integer printed = blankNumber(joined, i);
            int number = printed == null ? ctx.lastNumber + 1 : printed;
            if (ctx.explicitNumbers.contains(number) || ctx.byNumber.containsKey(number)) {
                // 这个题号卷面上已经另有一道题，空不再顺推，避免撞号与后面答案错位
                warnings.add("「" + sub.title + "」还有 " + (blanks - i) + " 个空未成题：题号 " + number
                        + " 已被卷面其他题目占用，请核对这一节的小题数");
                break;
            }
            ctx.lastNumber = Math.max(ctx.lastNumber, number);
            int sourceLine = blankLine(i, content);
            // 每个空都标上题号，否则同一篇材料的 10 道题看起来一模一样
            String stem = labelBlank(joined, i, number);
            out.add(register(new ParsedItem(number, sourceLine, stem, options, null, points), ctx));
        }
        return out;
    }

    private static int blankLine(int index, List<DocumentTextExtractor.SourceLine> content) {
        int seen = 0;
        for (DocumentTextExtractor.SourceLine line : content) {
            if (OPTION_LINE.matcher(strip(line.text())).matches()) {
                continue;
            }
            int here = countBlanks(line.text());
            if (index < seen + here) {
                return line.number();
            }
            seen += here;
        }
        return content.isEmpty() ? 1 : content.get(0).number();
    }

    // ------------------------------------------------------------- 写作题

    /** 一道写作任务：题号（0 = 顺序编号）+ 标题 + 正文行 + 分值。 */
    private static final class WritingTask {
        private final int number;
        private final int sourceLine;
        private final String title;
        private final List<DocumentTextExtractor.SourceLine> body;
        private final BigDecimal points;

        private WritingTask(int number, int sourceLine, String title,
                            List<DocumentTextExtractor.SourceLine> body, BigDecimal points) {
            this.number = number;
            this.sourceLine = sourceLine;
            this.title = title == null ? "" : title.strip();
            this.body = body;
            this.points = points;
        }
    }

    /**
     * 写作 / 口语部分 → ESSAY 题项（D-23）。
     *
     * <p>粒度优先级：① 带题号的写作任务行（{@code 15. 应用文写作（满分 15 分）}）一行一题；
     * ② 没有题号时按「第X节」一节一题；③ 都没有时整块 1 题并提醒。
     * 节内的 {@code 1. 表达理解；} 这类编号行是内容要点，不是新题。</p>
     */
    private static List<ParsedItem> interpretWriting(Block block, Context ctx, List<String> warnings) {
        List<DocumentTextExtractor.SourceLine> all = new ArrayList<>();
        for (Sub sub : block.subs) {
            all.addAll(sub.lines);
        }
        List<WritingTask> tasks = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Matcher number = NUMBER_LINE.matcher(strip(all.get(i).text()));
            if (number.matches() && isWritingTaskLine(number.group(2))) {
                starts.add(i);
            }
        }
        if (!starts.isEmpty()) {
            for (int k = 0; k < starts.size(); k++) {
                int index = starts.get(k);
                Matcher number = NUMBER_LINE.matcher(strip(all.get(index).text()));
                if (!number.matches()) {
                    continue;
                }
                int value = parseNumber(number.group(1), all.get(index).number());
                int end = k + 1 < starts.size() ? starts.get(k + 1) : all.size();
                String rest = number.group(2).strip();
                tasks.add(new WritingTask(value, all.get(index).number(), rest,
                        all.subList(index + 1, end), taskPoints(rest)));
            }
        } else {
            List<Sub> sections = block.subs.stream()
                    .filter(sub -> !sub.title.isBlank() && sub.lines.stream()
                            .anyMatch(line -> !strip(line.text()).isBlank()))
                    .toList();
            boolean hasSubHeaders = block.subs.stream().skip(1).anyMatch(sub -> !sub.title.isBlank());
            if (hasSubHeaders) {
                for (Sub sub : sections) {
                    BigDecimal points = taskPoints(sub.rawTitle);
                    tasks.add(new WritingTask(0, firstLine(sub.lines), sub.title, sub.lines,
                            points != null ? points : sub.points));
                }
            } else if (!all.isEmpty()) {
                tasks.add(new WritingTask(0, firstLine(all), cleanTitle(block.title), all,
                        taskPoints(block.title)));
                warnings.add("「" + block.title + "」没有写作题号也没有小节，已按一整题导入");
            }
        }
        if (tasks.isEmpty()) {
            warnings.add("「" + block.title + "」没有识别到写作任务，已跳过");
            return List.of();
        }

        BigDecimal partTotal = taskPoints(block.title);
        BigDecimal perTask = partTotal == null ? null
                : partTotal.divide(BigDecimal.valueOf(tasks.size()), 2, java.math.RoundingMode.HALF_UP);
        int cursor = ctx.lastNumber;
        List<ParsedItem> out = new ArrayList<>();
        for (WritingTask task : tasks) {
            int number = task.number > 0 ? task.number : ++cursor;
            Integer previous = ctx.firstLine.get(number);
            if (previous != null || ctx.byNumber.containsKey(number)) {
                throw ImportException.atLine(task.sourceLine,
                        "题号 " + number + " 重复（首次出现在第 " + (previous == null ? 0 : previous) + " 行）");
            }
            BigDecimal points = task.points != null ? task.points : perTask;
            if (points == null || points.signum() <= 0) {
                warnings.add("写作题 " + number + " 没有写分值（满分 N 分 / (N points)），请在向导里补上");
                points = points == null ? BigDecimal.ZERO : points;
            }
            String stem = writingStem(task);
            String rubric = rubricFromTask(task);
            ctx.firstLine.put(number, task.sourceLine);
            ctx.lastNumber = Math.max(ctx.lastNumber, number);
            ctx.explicitNumbers.add(number);
            out.add(register(new ParsedItem(number, task.sourceLine, stem.isBlank() ? null : stem,
                    null, null, points, QuestionType.ESSAY, rubric, null), ctx));
        }
        return out;
    }

    /** 写作任务行的判定：带写作类词，或带「满分 N 分」——避免把内容要点当新题。 */
    private static boolean isWritingTaskLine(String rest) {
        return WRITING_TASK_HINT.matcher(rest).find() || TASK_SCORE.matcher(rest).find();
    }

    /** 任务行/小节标题里的分值：先认「满分 N 分」，再认任意「N 分 / N points」。 */
    private static BigDecimal taskPoints(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher score = TASK_SCORE.matcher(text);
        if (score.find()) {
            return numberOf(score.group(1));
        }
        Matcher any = ANY_POINTS.matcher(text);
        return any.find() ? numberOf(any.group(1)) : null;
    }

    private static int firstLine(List<DocumentTextExtractor.SourceLine> lines) {
        return lines.isEmpty() ? 1 : lines.get(0).number();
    }

    /** 写作题题干 = 任务标题 + 正文（去掉纯下划线作答行、分页符与纯分值说明行）。 */
    private static String writingStem(WritingTask task) {
        StringBuilder sb = new StringBuilder();
        if (!task.title.isBlank()) {
            sb.append(task.title);
        }
        for (DocumentTextExtractor.SourceLine line : task.body) {
            String text = strip(line.text());
            if (text.isEmpty() || BLANK_ANSWER_LINE.matcher(text).matches()
                    || text.equalsIgnoreCase("newpage") || isScoreOnlyLine(text)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.toString().strip();
    }

    /**
     * 题面已经写了分值的要点 → 预填题项级 rubric（D-24）。
     *
     * <p>只收「带分值」的短行：没有分值的要点只是内容清单，一律均分会生成一份假 rubric，
     * 不如留空让老师在第 4 步写。</p>
     */
    private static String rubricFromTask(WritingTask task) {
        List<String> criteria = new ArrayList<>();
        for (DocumentTextExtractor.SourceLine line : task.body) {
            String text = strip(line.text());
            if (text.isEmpty() || BLANK_ANSWER_LINE.matcher(text).matches()) {
                continue;
            }
            if (!ANY_POINTS.matcher(text).find()) {
                continue;
            }
            String cleaned = text.replaceFirst("^\\d+\\s*[.、．)）]\\s*", "").strip();
            if (cleaned.isEmpty() || cleaned.length() > 120) {
                continue;
            }
            criteria.add("- " + cleaned);
        }
        return criteria.isEmpty() ? null : String.join("\n", criteria);
    }

    // ---------------------------------------------------------------- 答案区

    /** 返回答案区第一行的下标；没有答案区时返回 lines.size()。 */
    private static int findAnswerZoneStart(List<DocumentTextExtractor.SourceLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String text = unwrapBrackets(strip(lines.get(i).text()));
            if (CHINESE_ANSWER_MARKER.matcher(text).matches()
                    || ENGLISH_ANSWER_MARKER.matcher(text).matches()) {
                return i;
            }
            Matcher chinese = CHINESE_ANSWER_START.matcher(text);
            if (chinese.matches()) {
                return i;
            }
            if (ENGLISH_ANSWER_START.matcher(text).matches()) {
                return i;
            }
            if (isAnswerHeading(text)) {
                return i;
            }
        }
        return lines.size();
    }

    /** 「试卷参考答案与解析 (卷末附录)」这类带前缀的答案区标题；短、且没有句末标点。 */
    private static boolean isAnswerHeading(String text) {
        if (text.length() > 40 || text.indexOf('。') >= 0 || text.indexOf('；') >= 0) {
            return false;
        }
        return ANSWER_HEADING_HINT.matcher(text).find();
    }

    /**
     * 解析答案区。
     *
     * <p>容错原则：答案区里本来就可能有小标题、范文、中文解析，它们不该让整卷导入失败。
     * 所以「解析不出」分两种——**以题号开头的行**说明是答案写坏了，直接报错；
     * 其他行按说明跳过。整份答案区一条都没解析出来时仍然报错，避免格式全错却静默通过。</p>
     */
    private static void parseAnswerLines(List<DocumentTextExtractor.SourceLine> lines, int answerStart,
                                         Map<Integer, AnswerValue> answers, List<String> warnings,
                                         List<Integer> questionNumbers, Set<Integer> freeTextNumbers,
                                         Set<Integer> essayNumbers, Map<Integer, String> references) {
        DocumentTextExtractor.SourceLine badLine = null;
        String badText = null;
        Integer currentEssay = null;
        List<String> sample = new ArrayList<>();
        boolean sawUnboundSample = false;
        for (int i = answerStart; i < lines.size(); i++) {
            DocumentTextExtractor.SourceLine line = lines.get(i);
            String text = strip(line.text());
            if (text.isEmpty()) {
                if (currentEssay != null) {
                    sample.add("");
                }
                continue;
            }
            text = stripAnswerPrefix(text);
            if (text.isEmpty()) {
                continue;
            }
            Integer leading = AnswerFreeText.leadingNumber(text);
            // D-25：答案区里以写作题号开头的行 = 该题参考范文的入口；其后到下一个写作题号
            // 或一条可解析的客观答案行，全部归该题。范文里以数字开头的行不再报错。
            if (leading != null && essayNumbers.contains(leading)) {
                flushSample(references, currentEssay, sample);
                currentEssay = leading;
                continue;
            }
            if (currentEssay != null) {
                if (leading != null && isParseableAnswer(text)) {
                    flushSample(references, currentEssay, sample);
                    currentEssay = null;
                } else {
                    sample.add(text);
                    continue;
                }
            }
            if ((text.contains("范文") || text.contains("示范")) && !essayNumbers.isEmpty()) {
                sawUnboundSample = true;
            }
            // 填空题：整行按题号切成若干条文本答案
            if (leading != null && freeTextNumbers.contains(leading)) {
                for (AnswerFreeText.Entry entry : AnswerFreeText.parse(text)) {
                    if (entry.value().isEmpty()) {
                        warnings.add("第 " + line.number() + " 行：题号 " + entry.number() + " 没有答案，已跳过");
                    } else {
                        putAnswer(answers, warnings, entry.number(), entry.value(), line.number());
                    }
                }
                continue;
            }
            if (LETTER_SEQUENCE.matcher(text).matches() && normalizeLetters(text).length() >= 2) {
                parseLetterSequence(text, line, answers, warnings, questionNumbers);
                continue;
            }
            Matcher matcher = ANSWER_PAIR.matcher(text);
            int last = 0;
            int matched = 0;
            while (matcher.find()) {
                matched++;
                String gap = text.substring(last, matcher.start());
                if (containsDigit(gap)) {
                    Integer skipped = AnswerFreeText.bareNumber(gap);
                    if (skipped != null) {
                        warnings.add("第 " + line.number() + " 行：题号 " + skipped + " 没有答案，已跳过");
                    }
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
            if (matched == 0) {
                Integer skipped = AnswerFreeText.bareNumber(text);
                if (skipped != null) {
                    warnings.add("第 " + line.number() + " 行：题号 " + skipped + " 没有答案，已跳过");
                } else if (AnswerFreeText.leadingNumber(text) != null) {
                    // 以题号开头却解析不出：这是答案写坏了，报错
                    throw ImportException.atLine(line.number(),
                            "无法解析的答案行「" + text + "」（支持 1. A / 1A / 1 A / 1-5 ABCDA / 1 A 2 B 3 C）");
                }
                // 其余是答案区里的小标题/范文/解析，直接跳过
                continue;
            }
            String tail = text.substring(last);
            if (containsDigit(tail) && AnswerFreeText.bareNumber(tail) == null) {
                if (badLine == null) {
                    badLine = line;
                    badText = tail.strip();
                }
            }
        }
        flushSample(references, currentEssay, sample);
        if (sawUnboundSample && references.isEmpty()) {
            warnings.add("答案区里的写作示范范文没有题号，未保存；写成「15. …」可绑定到对应写作题");
        }
        if (answers.isEmpty() && badLine != null) {
            throw ImportException.atLine(badLine.number(),
                    "无法解析的答案内容「" + badText + "」（支持 1. A / 1A / 1 A / 1-5 ABCDA / 1 A 2 B 3 C）");
        }
    }

    /** 一行能不能按客观答案解析：能解析的行表示范文结束、答案区继续。 */
    private static boolean isParseableAnswer(String text) {
        if (LETTER_SEQUENCE.matcher(text).matches()) {
            return true;
        }
        if (ANSWER_PAIR.matcher(text).find()) {
            return true;
        }
        return AnswerFreeText.bareNumber(text) != null;
    }

    /** 把一段范文写进题号对应的缓冲；空行只在段间保留，首尾去掉。 */
    private static void flushSample(Map<Integer, String> references, Integer number, List<String> sample) {
        if (number == null) {
            sample.clear();
            return;
        }
        String joined = String.join("\n", sample).strip();
        if (!joined.isEmpty()) {
            references.merge(number, joined, (first, second) -> first + "\n\n" + second);
        }
        sample.clear();
    }

    /**
     * 纯字母串（「ABCDA」或「A B C D A」）按出现顺序配给答案区之前的最后 N 道题。
     *
     * <p>只有这 N 道题的题号连续时才配，不连续就告警并给出可改写的建议——错位配对会产生
     * 静默错分，比直接失败更糟。</p>
     */
    private static void parseLetterSequence(String text, DocumentTextExtractor.SourceLine line,
                                            Map<Integer, AnswerValue> answers, List<String> warnings,
                                            List<Integer> questionNumbers) {
        String letters = normalizeLetters(text);
        int count = letters.length();
        if (questionNumbers.size() < count) {
            throw ImportException.atLine(line.number(), "纯字母串有 " + count + " 个答案，但之前只识别到 "
                    + questionNumbers.size() + " 道题");
        }
        List<Integer> tail = questionNumbers.subList(questionNumbers.size() - count, questionNumbers.size());
        for (int k = 1; k < tail.size(); k++) {
            if (tail.get(k) != tail.get(k - 1) + 1) {
                StringJoiner seen = new StringJoiner(", ");
                for (Integer number : tail) {
                    seen.add(String.valueOf(number));
                }
                warnings.add("第 " + line.number() + " 行：纯字母串无法对应到连续题号（识别到的题号 "
                        + seen + "），请逐题写成「1 A」这种形式");
                return;
            }
        }
        for (int k = 0; k < count; k++) {
            putAnswer(answers, warnings, tail.get(k), String.valueOf(letters.charAt(k)), line.number());
        }
    }

    /** 「【答案】」这类方括号包裹的标记行，剥掉括号再判。 */
    private static String unwrapBrackets(String text) {
        String t = text.strip();
        if (!t.startsWith("【")) {
            return text;
        }
        int close = t.indexOf('】');
        if (close <= 0) {
            return text;
        }
        String head = t.substring(1, close).strip();
        String rest = t.substring(close + 1).strip();
        return rest.isEmpty() ? head : head + " " + rest;
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

    /** 答案区里按写作题号收集到的参考范文（D-25）写回题项。 */
    private static void applyReferences(Map<Integer, ParsedItem> byNumber, Map<Integer, String> references) {
        for (Map.Entry<Integer, String> entry : references.entrySet()) {
            ParsedItem item = byNumber.get(entry.getKey());
            String reference = entry.getValue() == null ? "" : entry.getValue().strip();
            if (item == null || reference.isEmpty()) {
                continue;
            }
            byNumber.put(entry.getKey(), new ParsedItem(item.number(), item.sourceLine(), item.stem(),
                    item.options(), item.answerKey(), item.points(), item.questionType(),
                    item.rubricMarkdown(), reference));
        }
    }

    private static void applyAnswers(Map<Integer, ParsedItem> byNumber, Map<Integer, AnswerValue> answers,
                                     List<String> warnings) {
        // 先抽题干内答案并把它从题干里删掉，再与答案区合并；题干内答案优先。
        List<Integer> numbers = new ArrayList<>(byNumber.keySet());
        for (Integer number : numbers) {
            ParsedItem item = byNumber.get(number);
            if (item == null) {
                continue;
            }
            Inline inline = extractInlineAnswer(item.stem());
            if (inline.inlineKey != null) {
                byNumber.put(number, new ParsedItem(item.number(), item.sourceLine(), inline.stem,
                        item.options(), inline.inlineKey, item.points()));
            }
        }
        for (Map.Entry<Integer, AnswerValue> entry : answers.entrySet()) {
            ParsedItem item = byNumber.get(entry.getKey());
            if (item == null) {
                warnings.add("第 " + entry.getValue().line + " 行：答案题号 " + entry.getKey()
                        + " 没有对应题目，已忽略");
                continue;
            }
            if (item.answerKey() != null && !item.answerKey().equals(entry.getValue().value)) {
                warnings.add("题号 " + item.number() + " 的题干内答案 " + item.answerKey() + " 与答案区 "
                        + entry.getValue().value + " 不一致，按题干内答案为准");
                continue;
            }
            byNumber.put(item.number(), new ParsedItem(item.number(), item.sourceLine(), item.stem(),
                    item.options(), entry.getValue().value, item.points()));
        }
    }

    private record Inline(String stem, String inlineKey) {
    }

    /** 从题干里抽出「（答案：X）」并从题干文本中删掉。 */
    private static Inline extractInlineAnswer(String stem) {
        if (stem == null) {
            return new Inline(null, null);
        }
        Matcher matcher = INLINE_ANSWER.matcher(stem);
        StringJoiner answers = new StringJoiner("");
        StringBuilder cleaned = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String value = normalizeLetters(matcher.group(1));
            if (!value.isEmpty()) {
                answers.add(value);
                cleaned.append(stem, last, matcher.start());
                last = matcher.end();
            }
        }
        if (last == 0) {
            return new Inline(stem, null);
        }
        cleaned.append(stem.substring(last));
        String text = cleaned.toString().strip();
        return new Inline(text.isEmpty() ? null : text, answers.toString().isEmpty() ? null : answers.toString());
    }

    // ------------------------------------------------------------------ 工具

    private static ParsedItem register(ParsedItem item, Context ctx) {
        ctx.items.add(item);
        ctx.byNumber.put(item.number(), item);
        return item;
    }

    /** 题项最终归属：按题号把更新后的题干/答案放回对应的部分。 */
    private static List<ParsedSection> rebuild(List<ParsedSection> sections, List<ParsedItem> finalized) {
        Map<Integer, ParsedItem> byNumber = new LinkedHashMap<>();
        for (ParsedItem item : finalized) {
            byNumber.put(item.number(), item);
        }
        List<ParsedSection> out = new ArrayList<>(sections.size());
        for (ParsedSection section : sections) {
            List<ParsedItem> items = new ArrayList<>(section.items().size());
            for (ParsedItem item : section.items()) {
                items.add(byNumber.getOrDefault(item.number(), item));
            }
            out.add(new ParsedSection(section.title(), section.pointsPerItem(), List.copyOf(items)));
        }
        return out;
    }

    private static String optionsText(Map<Character, StringBuilder> options) {
        if (options.isEmpty()) {
            return null;
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (Map.Entry<Character, StringBuilder> entry : options.entrySet()) {
            joiner.add(entry.getKey() + ". " + entry.getValue().toString().strip());
        }
        return joiner.toString();
    }

    private static String optionsText(List<DocumentTextExtractor.SourceLine> optionLines) {
        StringJoiner joiner = new StringJoiner("\n");
        for (DocumentTextExtractor.SourceLine line : optionLines) {
            String text = strip(line.text());
            Matcher matcher = OPTION_LINE.matcher(text);
            if (matcher.matches()) {
                joiner.add(matcher.group(1) + ". " + matcher.group(2).strip());
            }
        }
        return joiner.toString();
    }

    /**
     * 材料标签行：阅读理解前面的 {@code A} / {@code B}，剥掉 Markdown 的 {@code **A**} 之后剩下的。
     *
     * <p>只用来切分材料，不会写进题干（2026-10-21 修）：写进去会让该篇每道题开头都多一个
     * 孤立字母，学生与老师都会把它误认成标准答案。</p>
     */
    private static boolean isMaterialLabel(String text) {
        return text.length() == 1 && text.charAt(0) >= 'A' && text.charAt(0) <= 'G';
    }

    /**
     * 把第 {@code index} 个空换成 {@code ___(题号)___}，其余空收成统一写法。
     *
     * <p>卷面通常已经带了题号（{@code ______ (7)} / {@code （7）______} / {@code ______(7)___}）。
     * 整个「空 + 括号题号 + 下划线」都算同一个空（见 {@link #BLANK}），所以整段一起换掉：
     * 快照里既不会留下 {@code ___(7)___ (7)}，也不会留下题号两边多出来的下划线
     * （2026-10-21 / 10-22 修）。括号里不是题号时（{@code ______ (2019)}）不会被并进来。</p>
     *
     * <p>没轮到的空也收成 {@code ______} / {@code ______ (8)}：同一段材料在每道题的题干里各存一份，
     * 只有统一写法，答题页才能把材料认出「同一篇」并只渲染一次（{@code MaterialGrouper}）；
     * 题号两边多出来的下划线如果留着，空数会被数多，分组直接退化成每题重复一遍材料。</p>
     */
    static String labelBlank(String material, int index, int number) {
        Matcher matcher = BLANK.matcher(material);
        StringBuilder out = new StringBuilder(material.length());
        int seen = 0;
        int cursor = 0;
        while (matcher.find()) {
            out.append(material, cursor, matcher.start());
            String printed = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (seen == index) {
                out.append("___(").append(number).append(")___");
            } else if (printed != null) {
                // 别人题号保留成提示，MaterialGrouper 靠它把同一篇材料对齐
                out.append("______ (").append(printed).append(')');
            } else {
                out.append("______");
            }
            cursor = matcher.end();
            seen++;
        }
        return out.append(material.substring(cursor)).toString();
    }

    /** 第 {@code index} 个空自带的题号；卷面没写题号时返回 {@code null}。 */
    private static Integer blankNumber(String material, int index) {
        Matcher matcher = BLANK.matcher(material);
        int seen = 0;
        while (matcher.find()) {
            if (seen == index) {
                String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                return raw == null ? null : Integer.valueOf(raw);
            }
            seen++;
        }
        return null;
    }

    private static int countBlanks(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Matcher matcher = BLANK.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** 去掉标题里的括号说明：{@code 第一部分 听力（共两节，满分 30 分）} → {@code 第一部分 听力}。 */
    private static String cleanTitle(String text) {
        String cleaned = text.replaceAll("[（(][^（()）]*[)）]", " ").replaceAll("\\s+", " ").strip();
        return cleaned.isEmpty() ? text.strip() : cleaned;
    }

    private static BigDecimal pointsOf(String text) {
        Matcher matcher = POINTS_PER_ITEM.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        for (int group = 1; group <= matcher.groupCount(); group++) {
            if (matcher.group(group) != null) {
                return numberOf(matcher.group(group));
            }
        }
        return null;
    }

    /**
     * 把捕获到的数字段转成数值：阿拉伯数字直接解析，中文数字按「十 / 百 / 点」组合（T455）。
     *
     * <p>{@code 一} → 1、{@code 两} → 2、{@code 十五} → 15、{@code 二十五} → 25、
     * {@code 一百零五} → 105、{@code 一点五} → 1.5。</p>
     */
    private static BigDecimal numberOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        if (Character.isDigit(text.charAt(0))) {
            return new BigDecimal(text);
        }
        return chineseDecimal(text);
    }

    private static BigDecimal chineseDecimal(String text) {
        int dot = text.indexOf('点');
        if (dot < 0) {
            return BigDecimal.valueOf(chineseInteger(text));
        }
        StringBuilder fraction = new StringBuilder();
        for (int i = dot + 1; i < text.length(); i++) {
            Integer digit = CHINESE_DIGITS.get(text.charAt(i));
            if (digit == null) {
                return null;
            }
            fraction.append(digit);
        }
        return new BigDecimal(chineseInteger(text.substring(0, dot)) + "." + fraction);
    }

    /** 中文整数：{@code 十五} → 15、{@code 一百零五} → 105；分值量级只到百位。 */
    private static int chineseInteger(String text) {
        int total = 0;
        int number = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '百') {
                total += (number == 0 ? 1 : number) * 100;
                number = 0;
            } else if (c == '十') {
                total += (number == 0 ? 1 : number) * 10;
                number = 0;
            } else {
                Integer digit = CHINESE_DIGITS.get(c);
                if (digit != null) {
                    number = digit;
                }
            }
        }
        return total + number;
    }

    /**
     * 标题行里三个数字的自检：题数 × 每题分值 = 满分（T454）。
     *
     * <p>只发提醒，不改分值、不阻断导入——卷面把「共 5 小题；每小题 1.5 分，满分 7.5 分」
     * 写错时老师要能看到，但导入照走（2026-10-25 用户定：{@code 5 × 1.5 == 7.5} 用来自检，
     * 不是闸门）。三个数字缺任意一个就不自检，部分标题「（共两节，满分 30 分）」因此不会误报。</p>
     */
    private static void checkScoreArithmetic(String rawTitle, int line, List<String> warnings) {
        BigDecimal count = firstNumber(ITEM_COUNT_IN_TITLE, rawTitle);
        BigDecimal perItem = pointsOf(rawTitle);
        BigDecimal total = firstNumber(TOTAL_SCORE_IN_TITLE, rawTitle);
        if (count == null || perItem == null || total == null || count.signum() <= 0) {
            return;
        }
        BigDecimal computed = perItem.multiply(count);
        if (computed.compareTo(total) == 0) {
            return;
        }
        warnings.add("第 " + line + " 行「" + rawTitle + "」的分值对不上："
                + plain(count) + " 题 × " + plain(perItem) + " 分 = " + plain(computed)
                + " 分，标题写的是满分 " + plain(total) + " 分；已按每题 " + plain(perItem)
                + " 分导入，请核对");
    }

    private static BigDecimal firstNumber(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        for (int group = 1; group <= matcher.groupCount(); group++) {
            if (matcher.group(group) != null) {
                return numberOf(matcher.group(group));
            }
        }
        return null;
    }

    /**
     * 整行只讲分值/题数时为 true：{@code 每小题 1.5 分}、
     * {@code 共 5 小题；每小题 1.5 分，满分 7.5 分}。
     *
     * <p>这种行是排版说明，不进材料、不进题干（T456）；但必须真能读到一个分值/题数/满分数字，
     * 否则「分」「题」这种孤立单字也会被当成说明行删掉。</p>
     */
    private static boolean isScoreOnlyLine(String text) {
        if (text == null || text.isBlank() || !SCORE_LINE.matcher(text).matches()) {
            return false;
        }
        return pointsOf(text) != null
                || firstNumber(ITEM_COUNT_IN_TITLE, text) != null
                || firstNumber(TOTAL_SCORE_IN_TITLE, text) != null;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static Integer countOf(String text) {
        Matcher matcher = SUB_QUESTION_COUNT.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        BigDecimal count = numberOf(matcher.group(1));
        return count == null ? null : count.intValue();
    }

    private static int parseNumber(String raw, int line) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw ImportException.atLine(line, "题号不是有效整数：" + raw);
        }
    }

    private static String strip(String raw) {
        return raw == null ? "" : raw.strip();
    }

    private static String stripAnswerPrefix(String text) {
        String marker = unwrapBrackets(text);
        if (CHINESE_ANSWER_MARKER.matcher(marker).matches()
                || ENGLISH_ANSWER_MARKER.matcher(marker).matches()) {
            return "";
        }
        Matcher chinese = CHINESE_ANSWER_START.matcher(marker);
        if (chinese.matches()) {
            return chinese.group(1) == null ? "" : chinese.group(1).strip();
        }
        Matcher english = ENGLISH_ANSWER_START.matcher(marker);
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
}
