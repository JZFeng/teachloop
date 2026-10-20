package com.teachloop.report.service.export;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 导出用的只读视图（T120 / T121 / T124）。
 *
 * <p>全部字段来自 {@link ExportReadService} 的只读 SQL，不引用任何业务模块的 @Entity；
 * 这样 report 仍遵守 plan §3.2「跨模块只读聚合」的豁免，只读不写。
 */
public final class ExportModels {

    private ExportModels() {
    }

    /** 一场考试 / 一次布置的基础信息。标题取 papers.title。 */
    public record OfferingMeta(UUID id, String paperTitle, String kind, Instant dueAt,
                               boolean publishScores) {
    }

    /**
     * 按班统计的一行，口径与 {@code GET /api/offerings/{id}/class-summary} 完全一致：
     * 每个学生取 attempt_no 最大的一次提交（含草稿），再按「已交卷且未拒收」计入实交。
     *
     * @param onTimeRate  按时提交率 0–100，保留 2 位；实交为 0 时 null
     * @param averageScore 平均分，保留 2 位；{@code publish_scores=false} 或实交为 0 时 null
     */
    public record ClassStat(String classId, String className, int expected, long submitted,
                            long onTime, long late, long rejected,
                            BigDecimal onTimeRate, BigDecimal averageScore) {
    }

    /** 一位学生最新一次「已交卷且未拒收」的 attempt，逐份表与 ZIP 共用。 */
    public record StudentSubmission(UUID submissionId, UUID studentId, String studentName,
                                    int attemptNo, Instant submittedAt, long lateBySeconds,
                                    String gradingState, boolean autoSubmitted,
                                    BigDecimal objectiveTotal, BigDecimal subjectiveTotal,
                                    BigDecimal total) {
    }

    /** 一道错题：正确性只读 objective_answers.correct=false，已排除 ESSAY。 */
    public record WrongItem(int number, String questionType, String stem, String answer,
                            String answerKey, BigDecimal points) {
    }

    /** 未提交名单：在参与班级里，但没有任何「已交卷且未拒收」的 attempt。 */
    public record MissingStudent(UUID studentId, String studentName) {
    }

    /** 一份逐份反馈（Markdown 内容 + 安全文件名）。 */
    public record FeedbackDoc(String fileName, String markdown) {
    }

    public record FeedbackEntry(StudentSubmission submission, List<WrongItem> wrongItems,
                                String reviewComment) {
    }
}
