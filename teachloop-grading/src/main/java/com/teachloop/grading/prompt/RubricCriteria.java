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
 * T340 起与 {@code RubricValidator} 共用选择规则：有带分值的标题/列表项时，只取带分值的条目；
 * 没有分值的分组标题与说明行不算评分项。只有全文都没有分值时，才按满分均分。
 * 这是**过渡实现**：结构化评分项进端口后应改为直接读取。
 */
final class RubricCriteria {

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,4}\\s+(.+?)\\s*$");
    private static final Pattern BULLET = Pattern.compile("^\\s{0,3}[-*+]\\s+(.+?)\\s*$");
    /**
     * 有序列表行：「1. 内容」与「1 内容」都算。
     *
     * <p>第二种没有点是 Vditor 即时渲染视图的真实输出：老师敲「1. 」会被编辑器接成有序列表，
     * 回写的 Markdown 是「1 内容」（实测 2026-10-25，试卷向导评分项）。只认带点的写法会让
     * 老师刚写的分值在预览与判分里都看不见（T470）。
     */
    private static final Pattern NUMBERED = Pattern.compile("^\\s{0,3}\\d+(?:[.)、]\\s+|\\s+)(.+?)\\s*$");
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

        // T258：标题优先。`Rubric.md` 用 `### 1. Name — 2 points` 写评分项，
        // 下面的 `- Yes: 2 points` / `- No: 0 points` 是判定规则，不是评分项。
        // 只要本段里有可解析的标题，就不再从 bullet 里捡评分项；没有标题的旧 rubric
        // 仍走 bullet 解析，并过滤掉 Yes/No 这类判定行。
        List<Parsed> headings = new ArrayList<>();
        List<Parsed> bullets = new ArrayList<>();
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
                add(headings, heading.group(1));
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

        // T340：与 RubricValidator / 前端同一套选择规则（分组标题、说明行不算评分项）：
        //   1. 有带分值的标题 → 只取带分值的标题；
        //   2. 否则有带分值的列表项 → 只取带分值的列表项；
        //   3. 全文无分值 → 维持旧行为（标题优先，否则列表），由 allocate 均分。
        List<Parsed> pointedHeadings = headings.stream().filter(p -> p.maxPoints() != null).toList();
        List<Parsed> pointedBullets = bullets.stream().filter(p -> p.maxPoints() != null).toList();
        List<Parsed> parsed;
        if (!pointedHeadings.isEmpty()) {
            parsed = pointedHeadings;
        } else if (!pointedBullets.isEmpty()) {
            parsed = pointedBullets;
        } else {
            parsed = headings.isEmpty() ? bullets : headings;
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
        if (name.isEmpty() || name.length() > 500) {
            return;
        }
        if (IGNORED.contains(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        if (isDecisionRule(name)) {
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

    /** 二元 rubric 的“是/否”行、档位行不是评分项；旧 rubric 没有标题时靠这条过滤。 */
    private static boolean isDecisionRule(String name) {
        String lower = name.toLowerCase(Locale.ROOT).strip();
        // 「是否…」是中文 rubric 里最常见的问句式评分项，不是 Yes/No 判定行（与 RubricVocabulary 一致）。
        if (lower.startsWith("是否")) {
            return false;
        }
        return lower.equals("yes") || lower.equals("no") || lower.equals("true") || lower.equals("false")
                || lower.startsWith("yes ") || lower.startsWith("no ")
                || lower.startsWith("是") || lower.startsWith("否")
                || lower.startsWith("partial") || lower.startsWith("部分")
                || lower.startsWith("full") || lower.startsWith("完整")
                || lower.startsWith("none") || lower.startsWith("无");
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
