package com.teachloop.grading.ai;

import com.teachloop.grading.domain.ReviewText;
import com.teachloop.grading.prompt.SubjectiveGradeRequest;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T082 / T260：模型输出必须是契约 JSON，且与本次 rubric 对齐。
 *
 * <p>v2 在 v1 的「项名、顺序、满分、总分」之外，增加了质量闸门：
 * <ul>
 *   <li>每项 comment 必须含正文逐字引用（引号内原文，或 ≥4 词的连续原文）；</li>
 *   <li>comment/overall/strength/suggestion 不能为空，strength/suggestion 不能是套话；</li>
 *   <li>二元 rubric 只能给 0 或满分；</li>
 *   <li>篇幅项的 points 必须与服务端按班级政策算出的结论一致；</li>
 *   <li>反馈语言必须与目标语言一致（en 不出现整段中文，zh 必须有中文）。</li>
 * </ul>
 *
 * <p>任何一项不满足都抛 {@link InvalidGradeOutputException}，由引擎决定是否重试。
 */
public class GradeOutputValidator {

    private static final BigDecimal SUM_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal POINTS_TOLERANCE = new BigDecimal("0.001");
    private static final int MIN_COMMENT_CHARS = 15;
    private static final Pattern QUOTED = Pattern.compile(
            "[\"“”'‘’「」『』]([^\"“”'‘’「」『』]{3,200})[\"“”'‘’「」『』]");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}']+");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final List<String> GENERIC = List.of(
            "很好", "不错", "继续努力", "整体不错", "整体很好", "表现良好", "继续加油",
            "good job", "well done", "nice work", "keep it up", "keep up the good work");

    private final ObjectMapper mapper;

    public GradeOutputValidator() {
        this(new ObjectMapper());
    }

    GradeOutputValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Ports.GradingResult validate(String raw, SubjectiveGradeRequest request) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidGradeOutputException("模型输出为空");
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (RuntimeException ex) {
            throw new InvalidGradeOutputException("模型输出不是合法 JSON", ex);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidGradeOutputException("模型输出不是 JSON 对象");
        }

        JsonNode criteriaNode = root.path("criteria");
        if (!criteriaNode.isArray()) {
            throw new InvalidGradeOutputException("缺少 criteria 数组");
        }
        List<SubjectiveGradeRequest.Criterion> expected = request.criteria();
        if (criteriaNode.size() != expected.size()) {
            throw new InvalidGradeOutputException(
                    "criteria 数量不符：期望 " + expected.size() + "，实际 " + criteriaNode.size());
        }

        BigDecimal sum = BigDecimal.ZERO;
        List<Ports.CriterionScore> scores = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            SubjectiveGradeRequest.Criterion criterion = expected.get(i);
            JsonNode node = criteriaNode.get(i);
            if (node == null || !node.isObject()) {
                throw new InvalidGradeOutputException("criteria[" + i + "] 不是对象");
            }
            JsonNode nameNode = node.path("name");
            if (!nameNode.isString()) {
                throw new InvalidGradeOutputException("criteria[" + i + "].name 缺失或不是字符串");
            }
            String actualName = nameNode.asString().strip();
            if (!actualName.equals(criterion.name())) {
                throw new InvalidGradeOutputException(
                        "criteria[" + i + "].name 不符：期望「" + criterion.name() + "」，实际「" + actualName + "」");
            }
            JsonNode pointsNode = node.path("points");
            if (!pointsNode.isNumber()) {
                throw new InvalidGradeOutputException("criteria[" + i + "].points 缺失或不是数字");
            }
            BigDecimal points = pointsNode.decimalValue();
            if (points.signum() < 0 || points.compareTo(criterion.maxPoints()) > 0) {
                throw new InvalidGradeOutputException("criteria[" + i + "].points=" + points
                        + " 超出 0.." + criterion.maxPoints());
            }
            JsonNode maxNode = node.path("maxPoints");
            if (maxNode.isNumber()
                    && maxNode.decimalValue().subtract(criterion.maxPoints()).abs()
                    .compareTo(POINTS_TOLERANCE) > 0) {
                throw new InvalidGradeOutputException("criteria[" + i + "].maxPoints="
                        + maxNode.decimalValue() + " 与 rubric 的 " + criterion.maxPoints() + " 不符");
            }

            String comment = node.path("comment").isString() ? node.path("comment").asString().strip() : "";
            comment = checkedComment(i, criterion, comment, points, request);
            scores.add(new Ports.CriterionScore(criterion.name(), points, criterion.maxPoints(), comment));
            sum = sum.add(points);
        }

        JsonNode totalNode = root.path("total");
        if (!totalNode.isNumber()) {
            throw new InvalidGradeOutputException("total 缺失或不是数字");
        }
        BigDecimal total = totalNode.decimalValue();
        if (total.signum() < 0) {
            throw new InvalidGradeOutputException("total 不能为负数");
        }
        if (sum.subtract(total).abs().compareTo(SUM_TOLERANCE) > 0) {
            throw new InvalidGradeOutputException("total=" + total + " 与 criteria 求和 " + sum + " 不一致");
        }
        BigDecimal subjectiveMax = request.subjectiveMax();
        if (total.compareTo(subjectiveMax) > 0) {
            throw new InvalidGradeOutputException("total=" + total + " 超过主观题满分 " + subjectiveMax);
        }

        // 模型有时把「总评：」「优点：」「建议：」写进字段本身；字段名由界面渲染，
        // 这里先剥掉，落库的三段文本与学生反馈才不会出现「优点：优点：……」。
        String overall = ReviewText.stripLabel(text(root, "overall"));
        String strength = ReviewText.stripLabel(text(root, "strength"));
        String suggestion = ReviewText.stripLabel(text(root, "suggestion"));
        if (overall.isEmpty()) {
            throw new InvalidGradeOutputException("overall 缺失或为空");
        }
        requireFeedback(strength, "strength");
        requireFeedback(suggestion, "suggestion");
        checkLanguage(overall + " " + strength + " " + suggestion, request);

        String feedback = feedback(overall, strength, suggestion, request.feedbackLanguage());
        return new Ports.GradingResult(null, total, scores, feedback, List.of(), overall, strength, suggestion);
    }

    private String checkedComment(int index, SubjectiveGradeRequest.Criterion criterion, String comment,
                                  BigDecimal points, SubjectiveGradeRequest request) {
        if (isLengthCriterion(criterion.name())) {
            boolean inRange = request.paragraphCount() != null
                    && request.paragraphCount() >= request.lengthMin()
                    && request.paragraphCount() <= request.lengthMax();
            BigDecimal expectedPoints = inRange ? criterion.maxPoints() : BigDecimal.ZERO;
            if (points.subtract(expectedPoints).abs().compareTo(POINTS_TOLERANCE) > 0) {
                throw new InvalidGradeOutputException("篇幅项与代码结论不一致：段数 "
                        + request.paragraphCount() + "，应为 " + expectedPoints + " 分，模型给 " + points);
            }
            // 篇幅是机械判定项：用服务端的确定性句子覆盖模型的结构猜测，避免它编造“哪一段多余”。
            return lengthComment(request, criterion, inRange);
        }
        if (comment.isEmpty()) {
            throw new InvalidGradeOutputException("criteria[" + index + "].comment 为空");
        }
        if (comment.length() < MIN_COMMENT_CHARS) {
            throw new InvalidGradeOutputException("criteria[" + index + "].comment 太短（少于 "
                    + MIN_COMMENT_CHARS + " 字符）");
        }
        if (!hasVerbatimEvidence(comment, request.studentText())) {
            throw new InvalidGradeOutputException("criteria[" + index + "].comment 没有引用学生正文中的原句");
        }
        if (request.binaryRubric()
                && points.subtract(criterion.maxPoints()).abs().compareTo(POINTS_TOLERANCE) > 0
                && points.signum() != 0) {
            throw new InvalidGradeOutputException("二元 rubric 下 criteria[" + index + "].points=" + points
                    + " 既不是 0 也不是满分 " + criterion.maxPoints());
        }
        return comment;
    }

    private static String lengthComment(SubjectiveGradeRequest request,
                                        SubjectiveGradeRequest.Criterion criterion, boolean inRange) {
        boolean english = request.feedbackLanguage() != null
                && request.feedbackLanguage().toLowerCase(Locale.ROOT).startsWith("en");
        String count = String.valueOf(request.paragraphCount() == null ? 0 : request.paragraphCount());
        String range = request.lengthMin() + "–" + request.lengthMax();
        String boundary = request.paragraphBoundarySensitive() ? (english ? "; boundary-sensitive" : "；边界敏感") : "";
        if (english) {
            return "Server count: " + count + " paragraphs under " + request.paragraphPolicy() + boundary
                    + ". " + (inRange ? "Within the " + range + " paragraph range, so "
                    + criterion.maxPoints().stripTrailingZeros().toPlainString() + "/"
                    + criterion.maxPoints().stripTrailingZeros().toPlainString() + "."
                    : "Outside the " + range + " paragraph range, so 0/"
                    + criterion.maxPoints().stripTrailingZeros().toPlainString() + ".");
        }
        return "服务端按班级政策统计为 " + count + " 段（" + request.paragraphPolicy() + boundary + "），"
                + (inRange ? "在 " + range + " 段范围内，得 "
                + criterion.maxPoints().stripTrailingZeros().toPlainString() + " 分。"
                : "超出 " + range + " 段范围，得 0 分。");
    }

    private static boolean isLengthCriterion(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("length") || lower.contains("篇幅")
                || lower.contains("字数") || lower.contains("段");
    }

    /** 引号内原文优先；没有引号时接受 ≥4 词的连续原文，防止模型完全编造。 */
    private static boolean hasVerbatimEvidence(String comment, String studentText) {
        if (studentText == null || studentText.isBlank()) {
            return false;
        }
        String text = normalize(studentText);
        String note = normalize(comment);
        Matcher quoted = QUOTED.matcher(comment);
        while (quoted.find()) {
            String quote = normalize(quoted.group(1));
            if (quote.length() >= 3 && text.contains(quote)) {
                return true;
            }
        }
        List<String> tokens = tokens(studentText);
        for (int n = 4; n <= 10; n++) {
            for (int i = 0; i + n <= tokens.size(); i++) {
                String phrase = String.join(" ", tokens.subList(i, i + n));
                if (note.contains(phrase)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(normalize(text));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static void requireFeedback(String value, String field) {
        if (value.isEmpty()) {
            throw new InvalidGradeOutputException(field + " 缺失或为空");
        }
        String lower = value.toLowerCase(Locale.ROOT).strip();
        if (lower.length() < 8 || GENERIC.contains(lower)) {
            throw new InvalidGradeOutputException(field + " 过于笼统：' " + value + " '");
        }
    }

    private static void checkLanguage(String text, SubjectiveGradeRequest request) {
        boolean english = request.feedbackLanguage() != null
                && request.feedbackLanguage().toLowerCase(Locale.ROOT).startsWith("en");
        int cjk = 0;
        Matcher m = CJK.matcher(text);
        while (m.find()) {
            cjk++;
        }
        if (english && cjk > 0) {
            throw new InvalidGradeOutputException("目标语言是英文，但输出包含中文");
        }
        if (!english && cjk < 5) {
            throw new InvalidGradeOutputException("目标语言是中文，但输出没有足够的中文");
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isString() ? node.asString().strip() : "";
    }

    private static String feedback(String overall, String strength, String suggestion, String language) {
        boolean english = language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
        if (english) {
            return "Overall: " + overall + "\nStrength: " + strength + "\nSuggestion: " + suggestion;
        }
        return "总评：" + overall + "\n优点：" + strength + "\n建议：" + suggestion;
    }
}
