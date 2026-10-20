package com.teachloop.report.api;

import java.math.BigDecimal;
import java.util.List;

/** 报表对外契约（T152 / T192）。统计口径与数字全部来自只读聚合 SQL。 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * 一道客观题的常见错误统计。
     *
     * @param answered        作答人数（已按「每个学生最新 attempt」去重）
     * @param wrong           答错人数
     * @param correctRate     正确率 0–100，保留 1 位小数
     * @param topWrongAnswer  最常被选的错误答案；无人答错时为 null
     * @param topWrongCount   最常被选的错误答案被选次数；无人答错时为 0
     */
    public record WrongItemView(String itemId, int number, String stem, String questionType,
                                BigDecimal points, long answered, long wrong, BigDecimal correctRate,
                                String topWrongAnswer, long topWrongCount) {
    }

    /**
     * Top N 错题聚合结果。
     *
     * @param totalItems    统计范围内**有作答**的客观题总数（不受 limit 截断）
     * @param offeringCount 真正贡献了作答的场次数
     */
    public record WrongItemsView(List<WrongItemView> items, int totalItems, int offeringCount) {
    }
}
