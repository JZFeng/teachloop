package com.teachloop.grading.engine;

import com.teachloop.grading.prompt.SubjectiveGradeRequest;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 逐篇判分（D-26）：一道作文一次模型调用，结果合并成一份 {@link Ports.GradingResult}。
 *
 * <p>合并规则：</p>
 * <ul>
 *   <li>主观分 = 各篇之和；空白作文记 0、不调用；</li>
 *   <li>逐项评分带 {@code itemNumber}，复核页可分组；</li>
 *   <li>多篇时评语/总评/优点/建议按「第 N 题（满分 X 分）」分段（单篇不加前缀），模型与 prompt 版本取最后一次调用；</li>
 *   <li>任意一篇失败按整份失败处理（异常向上抛，由 worker 标 failed），不落假分。</li>
 * </ul>
 */
public final class SubjectiveGradingLoop {

    /** 判一道作文：真实引擎走模型，替身引擎按比例给分。 */
    @FunctionalInterface
    public interface EssayGrader {
        Ports.GradingResult grade(Ports.WorkItem work, Ports.ItemSpec essay);
    }

    private SubjectiveGradingLoop() {
    }

    public static Ports.GradingResult grade(Ports.WorkItem work, EssayGrader grader) {
        List<Ports.ItemSpec> essays = work.items().stream()
                .filter(item -> "ESSAY".equals(item.questionType()))
                .sorted(Comparator.comparingInt(Ports.ItemSpec::number))
                .toList();
        if (essays.isEmpty()) {
            // 全是客观题：没有可判的主观内容，也没有必要调用模型。
            return new Ports.GradingResult(null, BigDecimal.ZERO, List.of(), null, List.of());
        }

        BigDecimal total = BigDecimal.ZERO;
        List<Ports.CriterionScore> criteria = new ArrayList<>();
        List<Ports.ItemExplanation> explanations = new ArrayList<>();
        List<String> feedback = new ArrayList<>();
        List<String> overall = new ArrayList<>();
        List<String> strength = new ArrayList<>();
        List<String> suggestion = new ArrayList<>();
        String model = null;
        String promptVersion = null;
        String keySource = null;

        // 单篇作文不加「第 N 题（满分 X 分）」前缀：学生只写过这一篇，前缀是纯噪音；
        // 多篇才需要靠前缀区分段落归属。
        boolean multiEssay = essays.size() > 1;

        for (Ports.ItemSpec essay : essays) {
            String header = multiEssay ? header(essay) : "";
            String text = SubjectiveGradeRequest.essayText(work, essay);
            if (text.isBlank()) {
                feedback.add(header + "未作答。");
                continue;
            }
            Ports.GradingResult one = grader.grade(work, essay);
            total = total.add(nz(one.subjectivePoints()));
            for (Ports.CriterionScore criterion : nz(one.criteria())) {
                criteria.add(criterion.withItemNumber(essay.number()));
            }
            explanations.addAll(nz(one.explanations()));
            append(feedback, header, one.feedback());
            append(overall, header, one.overall());
            append(strength, header, one.strength());
            append(suggestion, header, one.suggestion());
            if (one.model() != null) {
                model = one.model();
            }
            if (one.promptVersion() != null) {
                promptVersion = one.promptVersion();
            }
            if (one.keySource() != null) {
                keySource = one.keySource();
            }
        }
        return new Ports.GradingResult(null, total, List.copyOf(criteria), join(feedback),
                List.copyOf(explanations), joinOrNull(overall), joinOrNull(strength), joinOrNull(suggestion),
                model, promptVersion, keySource);
    }

    /**
     * 多篇作文时的分段前缀。
     *
     * <p>写「满分 X 分」而不是「（X 分）」：这个数字是本题满分，不是得分，和成绩并排时容易误读。
     */
    private static String header(Ports.ItemSpec essay) {
        return "第 " + essay.number() + " 题（满分 " + plain(essay.points()) + " 分）：";
    }

    private static void append(List<String> target, String header, String value) {
        if (value != null && !value.isBlank()) {
            target.add(header + value.strip());
        }
    }

    private static String join(List<String> parts) {
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    private static String joinOrNull(List<String> parts) {
        return join(parts);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0"
                : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static <T> List<T> nz(List<T> value) {
        return value == null ? List.of() : value;
    }
}
