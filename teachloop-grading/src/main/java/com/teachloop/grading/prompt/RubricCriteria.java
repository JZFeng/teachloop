package com.teachloop.grading.prompt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 rubric Markdown 中解析评分项名称与满分。
 *
 * <p>目前 {@code Ports.PaperSnapshot}/{@code WorkItem} 只暴露 rubric 原文，没有结构化评分项
 * （{@code section_criteria} 尚未进端口）。因此这里按常见写法尽量解析：
 * <ul>
 *   <li>{@code ### 1. Required Length — 2 points}</li>
 *   <li>{@code - **内容要点**（15 分）：...}</li>
 *   <li>{@code - 内容要点：...}（无分值）</li>
 * </ul>
 * 缺失分值时会按剩余满分均分，并做一次确定性修正，保证各项满分之和等于主观题满分。
 * 这是**过渡实现**：结构化评分项进端口后应改为直接读取。
 */
final class RubricCriteria {

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,4}\\s+(.+?)\\s*$");
    private static final Pattern BULLET = Pattern.compile("^\\s{0,3}[-*+]\\s+(.+?)\\s*$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s{0,3}\\d+[.)、]\\s+(.+?)\\s*$");
    private static final Pattern MAX_POINTS =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:分|points?|pts?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\d+[.)、]\\s*");

    private static final Set<String> SECTION_START = Set.of(
            "评分标准", "评分项", "scoring rubric", "rubric", "grading rubric");
    private static final Set<String> SECTION_END = Set.of(
            "maximum score", "total score", "总分", "满分", "optional feedback", "反馈");
    private static final Set<String> IGNORED = Set.of(
            "assignment", "statement", "作业", "作业说明", "overall assignment rubric",
            "评分标准", "scoring rubric", "rubric", "maximum score", "total score",
            "总分", "满分", "optional feedback", "反馈", "grading rubric");

    private RubricCriteria() {
    }

    static List<SubjectiveGradeRequest.Criterion> parse(String markdown, BigDecimal subjectiveMax,
                                                        String feedbackLanguage) {
        boolean english = isEnglish(feedbackLanguage);
        String fallbackName = english ? "Overall subjective score" : "主观题评分";
        if (markdown == null || markdown.isBlank() || subjectiveMax.signum() <= 0) {
            return List.of(new SubjectiveGradeRequest.Criterion(fallbackName, subjectiveMax));
        }

        String[] lines = markdown.split("\\R");
        boolean hasStart = false;
        for (String line : lines) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches() && isStart(sectionName(heading.group(1)))) {
                hasStart = true;
                break;
            }
        }

        List<Parsed> parsed = new ArrayList<>();
        boolean inSection = !hasStart;
        for (String line : lines) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                String name = sectionName(heading.group(1));
                if (isStart(name)) {
                    inSection = true;
                    continue;
                }
                if (isEnd(name)) {
                    if (hasStart) {
                        break;
                    }
                    continue;
                }
                if (!inSection) {
                    continue;
                }
                add(parsed, heading.group(1));
                continue;
            }
            if (!inSection) {
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                add(parsed, bullet.group(1));
                continue;
            }
            Matcher numbered = NUMBERED.matcher(line);
            if (numbered.matches()) {
                add(parsed, numbered.group(1));
            }
        }

        if (parsed.isEmpty()) {
            return List.of(new SubjectiveGradeRequest.Criterion(fallbackName, subjectiveMax));
        }
        return allocate(parsed, subjectiveMax);
    }

    private static void add(List<Parsed> parsed, String raw) {
        String body = raw.replace("**", "").replace("__", "").replace("`", "").strip();
        if (body.isEmpty()) {
            return;
        }
        String withoutNumber = LEADING_NUMBER.matcher(body).replaceFirst("");
        Matcher maxMatcher = MAX_POINTS.matcher(withoutNumber);
        BigDecimal max = null;
        String name;
        if (maxMatcher.find()) {
            max = new BigDecimal(maxMatcher.group(1));
            name = withoutNumber.substring(0, maxMatcher.start());
        } else {
            name = withoutNumber;
        }
        name = cleanName(name);
        if (name.isEmpty() || name.length() > 80) {
            return;
        }
        if (IGNORED.contains(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        parsed.add(new Parsed(name, max));
    }

    private static String cleanName(String name) {
        String cleaned = name.strip();
        int colon = firstColon(cleaned);
        if (colon > 0) {
            cleaned = cleaned.substring(0, colon);
        }
        cleaned = cleaned.replaceAll("^[\\s\\-–—*_：:、（(]+", "");
        cleaned = cleaned.replaceAll("[\\s\\-–—*_：:、（）()]+$", "");
        return cleaned.strip();
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

    private static List<SubjectiveGradeRequest.Criterion> allocate(List<Parsed> parsed, BigDecimal subjectiveMax) {
        BigDecimal explicitSum = BigDecimal.ZERO;
        int missing = 0;
        for (Parsed p : parsed) {
            if (p.maxPoints() == null) {
                missing++;
            } else {
                explicitSum = explicitSum.add(p.maxPoints());
            }
        }
        BigDecimal remaining = subjectiveMax.subtract(explicitSum);
        if (remaining.signum() < 0 || (missing > 0 && remaining.signum() == 0)) {
            // rubric 中明示的分值超过（或刚好占满）试卷主观满分：明示值不可信，整体均分。
            return allocateEvenly(parsed, subjectiveMax);
        }
        if (missing == 0) {
            return parsed.stream()
                    .map(p -> new SubjectiveGradeRequest.Criterion(p.name(), p.maxPoints()))
                    .toList();
        }
        BigDecimal each = remaining.divide(BigDecimal.valueOf(missing), 1, RoundingMode.HALF_UP);
        List<SubjectiveGradeRequest.Criterion> result = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        int seenMissing = 0;
        for (Parsed p : parsed) {
            if (p.maxPoints() != null) {
                result.add(new SubjectiveGradeRequest.Criterion(p.name(), p.maxPoints()));
                continue;
            }
            seenMissing++;
            BigDecimal value = seenMissing == missing ? remaining.subtract(allocated) : each;
            allocated = allocated.add(value);
            result.add(new SubjectiveGradeRequest.Criterion(p.name(), value));
        }
        return result;
    }

    private static List<SubjectiveGradeRequest.Criterion> allocateEvenly(List<Parsed> parsed, BigDecimal subjectiveMax) {
        BigDecimal each = subjectiveMax.divide(BigDecimal.valueOf(parsed.size()), 1, RoundingMode.HALF_UP);
        List<SubjectiveGradeRequest.Criterion> result = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < parsed.size(); i++) {
            BigDecimal value = i == parsed.size() - 1 ? subjectiveMax.subtract(allocated) : each;
            allocated = allocated.add(value);
            result.add(new SubjectiveGradeRequest.Criterion(parsed.get(i).name(), value));
        }
        return result;
    }

    private static String sectionName(String raw) {
        String name = raw.replace("**", "").strip();
        name = LEADING_NUMBER.matcher(name).replaceFirst("");
        int colon = firstColon(name);
        if (colon > 0) {
            name = name.substring(0, colon);
        }
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean isStart(String name) {
        return SECTION_START.contains(name);
    }

    private static boolean isEnd(String name) {
        return SECTION_END.contains(name) || name.startsWith("maximum score");
    }

    private static boolean isEnglish(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
    }

    private record Parsed(String name, BigDecimal maxPoints) {
    }
}
