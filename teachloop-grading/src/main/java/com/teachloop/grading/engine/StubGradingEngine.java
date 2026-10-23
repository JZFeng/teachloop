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

    /** 与真实引擎同一条逐篇流程，只是「判一篇」换成固定比例（D-26）。 */
    @Override
    public Ports.GradingResult grade(Ports.WorkItem item) {
        return SubjectiveGradingLoop.grade(item, (work, essay) -> gradeOne(essay));
    }

    private Ports.GradingResult gradeOne(Ports.ItemSpec essay) {
        BigDecimal essayMax = essay.points() == null ? BigDecimal.ZERO : essay.points();
        BigDecimal subjective = essayMax.multiply(ratio).setScale(1, RoundingMode.HALF_UP);
        List<Ports.CriterionScore> criteria = new ArrayList<>();
        if (essayMax.signum() > 0) {
            BigDecimal contentMax = essayMax.multiply(new BigDecimal("0.6")).setScale(1, RoundingMode.HALF_UP);
            BigDecimal content = subjective.multiply(new BigDecimal("0.6")).setScale(1, RoundingMode.HALF_UP);
            BigDecimal language = subjective.subtract(content);
            criteria.add(new Ports.CriterionScore("内容要点", content, contentMax,
                    "stub：按固定比例给分"));
            criteria.add(new Ports.CriterionScore("语言准确性", language,
                    essayMax.subtract(contentMax).setScale(1, RoundingMode.HALF_UP),
                    "stub：按固定比例给分"));
        }
        return new Ports.GradingResult(null, subjective, criteria,
                "stub engine：这不是真实评分结果", List.of());
    }
}
