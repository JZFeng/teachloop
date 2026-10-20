package com.teachloop.sitting.service;

import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.TreeSet;

/**
 * 客观题计分（T196）：单选/填空大小写不敏感；多选支持全对才得分与 partial 两种策略。
 *
 * <p>partial 的公式是「对选 − 错选」占应选数的比例，最低 0 分不为负（AC-5.9）。
 * 所有规则都基于布置时的快照，历史标准答案变化不影响已交卷子。
 */
public final class ObjectiveScoring {

    private ObjectiveScoring() {
    }

    public static BigDecimal award(Ports.ItemSpec spec, String givenRaw) {
        if (spec.answerKey() == null || "ESSAY".equals(spec.questionType())) {
            return BigDecimal.ZERO;
        }
        String given = givenRaw == null ? "" : givenRaw.trim();
        if (given.isEmpty()) {
            return BigDecimal.ZERO;
        }
        if ("MULTIPLE_CHOICE".equals(spec.questionType())) {
            Set<String> chosen = tokens(given);
            Set<String> key = tokens(spec.answerKey());
            if (chosen.isEmpty() || key.isEmpty()) {
                return BigDecimal.ZERO;
            }
            if ("PARTIAL".equalsIgnoreCase(spec.scoringStrategy())) {
                int correct = 0;
                for (String c : chosen) {
                    if (key.contains(c)) {
                        correct++;
                    }
                }
                int wrong = chosen.size() - correct;
                int ratio = correct - wrong;
                if (ratio <= 0) {
                    return BigDecimal.ZERO;
                }
                return spec.points()
                        .multiply(BigDecimal.valueOf(ratio))
                        .divide(BigDecimal.valueOf(key.size()), 2, RoundingMode.HALF_UP)
                        .min(spec.points());
            }
            return MatchStrategies.CHOICE.matches(spec.answerKey(), given) ? spec.points() : BigDecimal.ZERO;
        }
        return MatchStrategies.NORMALIZED.matches(spec.answerKey(), given) ? spec.points() : BigDecimal.ZERO;
    }

    public static boolean fullyCorrect(Ports.ItemSpec spec, String given) {
        return award(spec, given).compareTo(spec.points()) == 0;
    }

    /** 选择题答案按「单个字母」切分：AB、A B、A,B 都等价（旧实现按连续字母切，AB 会变成一个 token）。 */
    static Set<String> tokens(String s) {
        Set<String> out = new TreeSet<>();
        for (char c : s.toUpperCase().toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                out.add(String.valueOf(c));
            }
        }
        return out;
    }
}
