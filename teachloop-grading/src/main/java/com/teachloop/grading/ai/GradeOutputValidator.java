package com.teachloop.grading.ai;

import com.teachloop.grading.prompt.SubjectiveGradeRequest;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T082：模型输出必须是契约 JSON，且与本次 rubric 对齐。
 *
 * <p>校验四件事：项名、顺序、每项满分、总分一致性（plan §6）。
 * 任何一项不满足都抛 {@link InvalidGradeOutputException}，由引擎决定是否重试。
 */
public class GradeOutputValidator {

    private static final BigDecimal SUM_TOLERANCE = new BigDecimal("0.01");

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
            JsonNode commentNode = node.path("comment");
            String comment = commentNode.isString() ? commentNode.asString() : "";
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

        JsonNode strengthNode = root.path("strength");
        JsonNode suggestionNode = root.path("suggestion");
        if (!strengthNode.isString() || !suggestionNode.isString()) {
            throw new InvalidGradeOutputException("strength 或 suggestion 缺失/不是字符串");
        }

        String feedback = feedback(strengthNode.asString().strip(), suggestionNode.asString().strip(),
                request.feedbackLanguage());
        return new Ports.GradingResult(null, total, scores, feedback, List.of());
    }

    private static String feedback(String strength, String suggestion, String language) {
        if (strength.isEmpty() && suggestion.isEmpty()) {
            return null;
        }
        boolean english = language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
        String strengthLabel = english ? "Strength: " : "优点：";
        String suggestionLabel = english ? "Suggestion: " : "建议：";
        return strengthLabel + strength + "\n" + suggestionLabel + suggestion;
    }
}
