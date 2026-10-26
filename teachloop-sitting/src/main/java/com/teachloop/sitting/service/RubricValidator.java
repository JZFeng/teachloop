package com.teachloop.sitting.service;

import com.teachloop.sitting.domain.RubricVocabulary;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 作业 rubric 的录入校验（T258）。
 *
 * <p>只做「能不能交给 AI 判」的结构校验，不评价标准写得好不好：
 * 解析评分项名称与分值，要求名称非空且不重复、各项之和等于作业总分。
 * 没有分值的分组标题 / 说明行不算评分项，进 {@code ignored}（只供界面提示，不阻断发布）。
 * 与 grading 的 {@code RubricCriteria}、前端 {@code rubric.ts} 共用同一套选择规则，
 * 夹具在 {@code docs/rubrics/fixtures/rubric-cases.json}。
 */
public final class RubricValidator {

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,4}\\s+(.+?)\\s*$");
    /** 无序列表行：{@code - 内容} 与 {@code -内容} 都算（T474，空格几乎总会被漏掉）。 */
    private static final Pattern BULLET = Pattern.compile("^\\s{0,3}[-*+]\\s*(.+?)\\s*$");
    /**
     * 有序列表行：「1. 内容」与「1 内容」都算。
     *
     * <p>第二种没有点是 Vditor 即时渲染视图的真实输出：老师敲「1. 」会被编辑器接成有序列表，
     * 回写的 Markdown 是「1 内容」（实测 2026-10-25，试卷向导评分项）。只认带点的写法会让
     * 老师刚写的分值在预览与判分里都看不见（T470）。
     */
    /**
     * 有序列表行：{@code 1. 内容}、{@code 1.内容}、{@code 1、内容}、{@code 1）内容}、
     * {@code 1 内容}、{@code （1）内容}、{@code ① 内容} 都算。
     *
     * <p>T474：老师手写「1.表达理解（5 分）」时点后面几乎不会带空格，原来的
     * {@code \\d+[.)、]\\s+} 会把整行静默丢掉；只要行首有编号标记，就允许紧跟内容。
     * 裸数字仍要求空格（{@code 1 内容}），否则 {@code 2026 年} 这类行会被当成评分项。</p>
     */
    private static final Pattern NUMBERED = Pattern.compile(
            "^\\s{0,3}(?:\\d+[.)、．）]\\s*|\\d+\\s+|\\d{1,2}(?=[^\\d\\s.、．)）])"
                    + "|[（(]\\d+[)）]\\s*|[①-⑳]\\s*)(.+?)\\s*$");
    /** 行首编号（含全角括号与圈号）：把 {@code 1. 内容}、{@code （1）内容}、{@code ① 内容} 剥成名称本身。 */
    private static final Pattern LEADING_NUMBER =
            Pattern.compile("^\\s*(?:\\d+[.)、．）]|[（(]\\d+[)）]|[①-⑳])\\s*");

    /**
     * 评分项名称的长度上限。原本是 80，但教师常用「整句问句」甚至一段说明当评分项
     * （例如 Did the learner include all the components…？ 共 95 字符），超过上限会被
     * 静默丢掉，最终报出「合计 8 分 / 总分 14 分」这种对不上号的错。
     * 500 之后基本只有「整段散文」才会撞线，正常评分项不会再被误杀。
     */
    private static final int MAX_NAME_LENGTH = 500;

    private RubricValidator() {
    }

    public record Criterion(String name, BigDecimal maxPoints) {
    }

    /** 一条校验问题：{@code code} 是消息 key，{@code args} 是消息参数。 */
    public record Issue(String code, List<Object> args) {

        public Issue {
            args = args == null ? List.of() : List.copyOf(args);
        }

        public static Issue of(String code, Object... args) {
            return new Issue(code, args == null ? List.of() : List.of(args));
        }
    }

    public record Result(boolean valid, List<Criterion> criteria, BigDecimal total,
                         List<Issue> issues, List<String> ignored) {
    }

    public static Result validate(String markdown, BigDecimal expectedTotal) {
        List<Criterion> criteria = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return new Result(false, List.of(), BigDecimal.ZERO,
                    List.of(Issue.of("error.rubric.empty")), List.of());
        }
        Parsed parsed = parse(markdown);
        if (parsed.criteria().isEmpty()) {
            return new Result(false, List.of(), BigDecimal.ZERO,
                    List.of(Issue.of("error.rubric.noCriteria")), ignoredNames(parsed.ignored()));
        }
        boolean anyPoints = parsed.criteria().stream().anyMatch(c -> c.maxPoints() != null);
        if (!anyPoints) {
            // 兼容历史 rubric（完全没有分值）：运行时 RubricCriteria 会均分；
            // 新作业模板已带分值，因此这条只服务存量数据与旧测试。
            return new Result(true, evenly(parsed.criteria(), expectedTotal),
                    expectedTotal == null ? BigDecimal.ZERO : expectedTotal, List.of(),
                    ignoredNames(parsed.ignored()));
        }
        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ParsedCriterion c : parsed.criteria()) {
            String key = c.name().toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                issues.add(Issue.of("error.rubric.duplicate", c.name()));
            }
            if (c.maxPoints() == null) {
                // 新选择规则下 criteria 要么全带分值、要么全不带；这里只做防御，正常不会触发。
                issues.add(Issue.of("error.rubric.missingPoints", c.name()));
            } else {
                total = total.add(c.maxPoints());
                criteria.add(new Criterion(c.name(), c.maxPoints()));
            }
        }
        if (expectedTotal != null && expectedTotal.signum() > 0
                && total.subtract(expectedTotal).abs().compareTo(new BigDecimal("0.01")) > 0) {
            issues.add(Issue.of("error.rubric.sumMismatch", pretty(total), pretty(expectedTotal)));
        }
        return new Result(issues.isEmpty(), List.copyOf(criteria), total, List.copyOf(issues),
                ignoredNames(parsed.ignored()));
    }

    /** 被忽略的「没有分值」条目：只用于界面提示，不参与校验、不阻断发布。 */
    private static List<String> ignoredNames(List<ParsedCriterion> items) {
        return items.stream().map(ParsedCriterion::name).distinct().toList();
    }

    private static List<Criterion> evenly(List<ParsedCriterion> parsed, BigDecimal expectedTotal) {
        if (parsed.isEmpty()) {
            return List.of();
        }
        BigDecimal max = expectedTotal == null ? BigDecimal.ZERO : expectedTotal;
        BigDecimal each = max.divide(BigDecimal.valueOf(parsed.size()), 2, java.math.RoundingMode.HALF_UP);
        List<Criterion> out = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < parsed.size(); i++) {
            BigDecimal value = i == parsed.size() - 1 ? max.subtract(allocated) : each;
            allocated = allocated.add(value);
            out.add(new Criterion(parsed.get(i).name(), value));
        }
        return out;
    }

    private static Parsed parse(String markdown) {
        List<String> lines = markdown.lines().toList();
        boolean hasStart = lines.stream()
                .map(RubricValidator::headingName)
                .anyMatch(RubricVocabulary::isStart);
        List<ParsedCriterion> headings = new ArrayList<>();
        List<ParsedCriterion> bullets = new ArrayList<>();
        boolean inSection = !hasStart;
        for (String line : lines) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                String name = headingName(line);
                if (RubricVocabulary.isStart(name)) {
                    inSection = true;
                    continue;
                }
                if (RubricVocabulary.isEnd(name)) {
                    if (hasStart) {
                        break;
                    }
                    continue;
                }
                if (inSection) {
                    add(headings, heading.group(1));
                }
                continue;
            }
            if (!inSection) {
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                add(bullets, bullet.group(1));
                continue;
            }
            Matcher numbered = NUMBERED.matcher(line);
            if (numbered.matches()) {
                add(bullets, numbered.group(1));
            }
        }

        // T340：分组标题 / 说明行不是评分项。选择规则（grading 与前端逐条对齐）：
        //   1. 有带分值的标题 → 评分项 = 带分值的标题；无分值标题与无分值列表项进 ignored；
        //   2. 没有带分值的标题、但有带分值的列表项 → 评分项 = 带分值的列表项；无分值项与标题进 ignored；
        //   3. 全文没有任何分值 → 维持旧行为（标题优先，否则列表），均分，ignored 为空。
        List<ParsedCriterion> pointedHeadings = headings.stream()
                .filter(c -> c.maxPoints() != null).toList();
        List<ParsedCriterion> pointedBullets = bullets.stream()
                .filter(c -> c.maxPoints() != null).toList();
        if (!pointedHeadings.isEmpty()) {
            List<ParsedCriterion> ignored = new ArrayList<>();
            headings.stream().filter(c -> c.maxPoints() == null).forEach(ignored::add);
            bullets.stream().filter(c -> c.maxPoints() == null).forEach(ignored::add);
            return new Parsed(List.copyOf(pointedHeadings), List.copyOf(ignored));
        }
        if (!pointedBullets.isEmpty()) {
            List<ParsedCriterion> ignored = new ArrayList<>();
            bullets.stream().filter(c -> c.maxPoints() == null).forEach(ignored::add);
            ignored.addAll(headings);
            return new Parsed(List.copyOf(pointedBullets), List.copyOf(ignored));
        }
        List<ParsedCriterion> fallback = headings.isEmpty() ? bullets : headings;
        return new Parsed(List.copyOf(fallback), List.of());
    }

    private static void add(List<ParsedCriterion> out, String raw) {
        String body = raw.replace("**", "").replace("__", "").replace("`", "").strip();
        if (body.isEmpty()) {
            return;
        }
        String withoutNumber = LEADING_NUMBER.matcher(body).replaceFirst("");
        Matcher points = RubricVocabulary.MAX_POINTS.matcher(withoutNumber);
        BigDecimal max = null;
        String name;
        if (points.find()) {
            max = new BigDecimal(points.group(1));
            name = withoutNumber.substring(0, points.start());
        } else {
            name = withoutNumber;
        }
        name = cleanName(name);
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || RubricVocabulary.isIgnored(name.toLowerCase(Locale.ROOT))
                || RubricVocabulary.isDecision(name)) {
            return;
        }
        out.add(new ParsedCriterion(name, max));
    }

    private static String headingName(String line) {
        Matcher m = HEADING.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1).replace("**", "").strip();
        name = LEADING_NUMBER.matcher(name).replaceFirst("");
        int colon = firstColon(name);
        if (colon > 0) {
            name = name.substring(0, colon);
        }
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static String cleanName(String raw) {
        String name = raw.strip();
        int colon = firstColon(name);
        if (colon > 0) {
            name = name.substring(0, colon);
        }
        name = name.replaceAll("^[\\s\\-–—*_：:、（(]+", "");
        name = name.replaceAll("[\\s\\-–—*_：:、（）()]+$", "");
        return name.strip();
    }

    private static int firstColon(String value) {
        int zh = value.indexOf('：');
        int en = value.indexOf(':');
        if (zh < 0) {
            return en;
        }
        if (en < 0) {
            return zh;
        }
        return Math.min(zh, en);
    }

    private static String pretty(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record ParsedCriterion(String name, BigDecimal maxPoints) {
    }

    private record Parsed(List<ParsedCriterion> criteria, List<ParsedCriterion> ignored) {
    }
}
