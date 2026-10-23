package com.teachloop.grading.prompt;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 写作题的**默认评分比例**（D-28）：内容与任务完成 + 语法与拼写。
 *
 * <p>没有题项级 rubric、也没有试卷级 rubric 时，判分不再均分，而是按这里的比例合成一份
 * 两项 rubric：内容占 {@code contentPercent}%（默认 80），语法与拼写占其余部分（默认 20）。
 * 比例来自配置 {@code teachloop.grading.essay-default-rubric.content-percent}，改完重启生效。</p>
 *
 * <p>为什么默认只分两项：老师要的是「把题目整个内容交给 AI 判是否写到了（大头），
 * 再顺带查基础语法与拼写（小头）」。需要更细的评分项时，在第 4 步给那道写作题写自己的 rubric，
 * 题项级 rubric 永远优先于这里。</p>
 */
public record EssayRubricWeights(int contentPercent) {

    /** 默认 80 / 20。 */
    public static final EssayRubricWeights DEFAULT = new EssayRubricWeights(80);

    public EssayRubricWeights {
        if (contentPercent < 1 || contentPercent > 99) {
            throw new IllegalArgumentException(
                    "teachloop.grading.essay-default-rubric.content-percent 必须在 1–99 之间（当前 "
                            + contentPercent + "）；语法与拼写取其余百分比");
        }
    }

    public int languagePercent() {
        return 100 - contentPercent;
    }

    /**
     * 按比例拆本题满分：内容四舍五入取整，语法与拼写取余数（保证两项相加等于满分）。
     *
     * <p>取整口径是用户 2026-10-23 明确要求的：14 分 80% = 11.2 → 11，语法 3；
     * 15 分 70% = 10.5 → 11（四舍五入），语法 4。不要给出 11.2 / 2.8 这种小数分。</p>
     */
    public BigDecimal contentPoints(BigDecimal total) {
        BigDecimal safe = total == null ? BigDecimal.ZERO : total;
        return safe.multiply(BigDecimal.valueOf(contentPercent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    public BigDecimal languagePoints(BigDecimal total) {
        BigDecimal safe = total == null ? BigDecimal.ZERO : total;
        return safe.subtract(contentPoints(safe));
    }

    /**
     * 合成默认 rubric（Markdown 列表，带分值）。判分侧用自己的解析器读，老师能在复核页看到；
     * 学生端不展示 rubric，只展示 AI 最终反馈。
     */
    public String defaultMarkdown(BigDecimal totalPoints, boolean english) {
        BigDecimal content = contentPoints(totalPoints);
        BigDecimal language = languagePoints(totalPoints);
        if (english) {
            return "# Rubric\n"
                    + "- Content and task completion (" + plain(content) + " points): whether the response "
                    + "fully addresses the task and covers every required point; judge only from the student's text.\n"
                    + "- Grammar and spelling (" + plain(language) + " points): basic grammar, spelling and "
                    + "punctuation; whether wording is appropriate for the task.\n";
        }
        return "# 评分标准\n"
                + "- 内容与任务完成（" + plain(content) + " 分）：是否完整回应题目要求、覆盖题目列出的全部要点；"
                + "只依据学生正文判断，不虚构正文里没有的内容。\n"
                + "- 语法与拼写（" + plain(language) + " 分）：基础语法、拼写与标点是否正确，用词是否得体。\n";
    }

    /**
     * 旧版 {@code PaperCatalogImpl} 在快照里伪造的那份「无分值默认 rubric」。
     *
     * <p>存量卷子（本改版之前布置的）快照里带着这段文本，它不是老师写的。判分时把它当成
     * 「没有 rubric」，否则会被均分成 50/50，配置的 80/20 对这些卷子永远不生效。</p>
     */
    private static final String LEGACY_DEFAULT = """
            # 评分标准
            - 内容要点：是否覆盖题目要求的全部要点。
            - 语言准确性：语法、用词与句式是否准确得体。""";

    /** 这段 rubric 是不是旧版系统伪造的默认值（不是老师写的）。 */
    public static boolean isLegacyDefault(String rubric) {
        if (rubric == null || rubric.isBlank()) {
            return false;
        }
        return normalize(rubric).equals(normalize(LEGACY_DEFAULT));
    }

    private static String normalize(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
