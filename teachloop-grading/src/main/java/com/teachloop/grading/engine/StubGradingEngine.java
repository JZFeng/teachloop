package com.teachloop.grading.engine;

import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地替身：不调用任何模型，按 ESSAY 题分值的一个固定比例给分。
 * 仅供集成测试与本地开发，生产用 DirectApiEngine（下一步接入）。
 */
public class StubGradingEngine implements GradingEngine {

    private final BigDecimal ratio;

    public StubGradingEngine(BigDecimal ratio) {
        this.ratio = ratio;
    }

    @Override
    public Ports.GradingResult grade(Ports.WorkItem item) {
        BigDecimal essayMax = item.items().stream()
                .filter(i -> "ESSAY".equals(i.questionType()))
                .map(Ports.ItemSpec::points)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal subjective = essayMax.multiply(ratio).setScale(1, RoundingMode.HALF_UP);
        List<Ports.CriterionScore> criteria = new ArrayList<>();
        if (essayMax.signum() > 0) {
            BigDecimal content = subjective.multiply(new BigDecimal("0.6")).setScale(1, RoundingMode.HALF_UP);
            BigDecimal language = subjective.subtract(content);
            criteria.add(new Ports.CriterionScore("内容要点", content,
                    essayMax.multiply(new BigDecimal("0.6")).setScale(1, RoundingMode.HALF_UP),
                    "stub：按固定比例给分"));
            criteria.add(new Ports.CriterionScore("语言准确性", language,
                    essayMax.subtract(essayMax.multiply(new BigDecimal("0.6"))).setScale(1, RoundingMode.HALF_UP),
                    "stub：按固定比例给分"));
        }
        return new Ports.GradingResult(null, subjective, criteria,
                "stub engine：这不是真实评分结果", List.of());
    }
}
