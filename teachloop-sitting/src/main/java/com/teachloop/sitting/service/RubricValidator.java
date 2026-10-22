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
 * 解析评分项名称与分值，要求名称非空且不重复、每项有显式分值、各项之和等于作业总分。
 * 与 grading 的 {@code RubricCriteria} 保持同一套「标题优先、Yes/No 是规则不是评分项」口径。
 */
public final class RubricValidator {

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,4}\\s+(.+?)\\s*$");
    private static final Pattern BULLET = Pattern.compile("^\\s{0,3}[-*+]\\s+(.+?)\\s*$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s{0,3}\\d+[.)、]\\s+(.+?)\\s*$");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\d+[.)、]\\s*");

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
                         List<Issue> issues) {
    }

    public static Result validate(String markdown, BigDecimal expectedTotal) {
        List<Criterion> criteria = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return new Result(false, List.of(), BigDecimal.ZERO, List.of(Issue.of("error.rubric.empty")));
        }
        Parsed parsed = parse(markdown);
        if (parsed.criteria().isEmpty()) {
            return new Result(false, List.of(), BigDecimal.ZERO,
                    List.of(Issue.of("error.rubric.noCriteria")));
        }
        boolean anyPoints = parsed.criteria().stream().anyMatch(c -> c.maxPoints() != null);
        if (!anyPoints) {
            // 兼容历史 rubric（完全没有分值）：运行时 RubricCriteria 会均分；
            // 新作业模板已带分值，因此这条只服务存量数据与旧测试。
            return new Result(true, evenly(parsed.criteria(), expectedTotal),
                    expectedTotal == null ? BigDecimal.ZERO : expectedTotal, List.of());
        }
        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ParsedCriterion c : parsed.criteria()) {
            String key = c.name().toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                issues.add(Issue.of("error.rubric.duplicate", c.name()));
            }
            if (c.maxPoints() == null) {
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
        return new Result(issues.isEmpty(), List.copyOf(criteria), total, List.copyOf(issues));
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
        return new Parsed(headings.isEmpty() ? bullets : headings);
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

    private record Parsed(List<ParsedCriterion> criteria) {
    }
}
