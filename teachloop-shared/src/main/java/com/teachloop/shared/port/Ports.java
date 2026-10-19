package com.teachloop.shared.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 跨模块端口的数据契约：只有 record，没有实体，避免懒加载跨模块泄漏。 */
public final class Ports {

    private Ports() {
    }

    /** 一道客观题或一道主观题的评分契约。 */
    public record ItemSpec(UUID itemId, int number, String questionType, String answerKey,
                           BigDecimal points, String stem) {
    }

    /** 学生的某一道题的作答。 */
    public record AnswerRow(UUID itemId, int number, String answer) {
    }

    /** 试卷只读快照（布置时固化，不回查活表）。 */
    public record PaperSnapshot(UUID paperId, String title, int totalPoints, int durationMinutes,
                                List<ItemSpec> items, String rubricMarkdown) {
    }

    /** 阅卷需要的一切，一次取齐（胖消息、瘦 Worker）。 */
    public record WorkItem(UUID submissionId, UUID offeringId, UUID studentId, String studentName,
                           String inputKind, String contentText, List<AnswerRow> answers,
                           List<ItemSpec> items, String rubricMarkdown, String gradingPrompt,
                           String feedbackLanguage) {
    }

    public record CriterionScore(String name, BigDecimal points, BigDecimal maxPoints, String comment) {
    }

    public record ItemExplanation(int number, String text) {
    }

    /**
     * @param objectivePoints 客观题得分；null 表示“不改动”，因为客观分在提交时已由代码判定，不允许模型覆盖
     */
    public record GradingResult(BigDecimal objectivePoints, BigDecimal subjectivePoints,
                                List<CriterionScore> criteria, String feedback,
                                List<ItemExplanation> explanations) {
    }

    /** sitting 暴露：阅卷读作答 + 领取/推进工作项（唯一命令式端口，方法必须幂等）。 */
    public interface SittingLedger {
        WorkItem load(UUID submissionId);

        /** 待批改的工作项（PENDING），返回上限 limit 条。 */
        List<UUID> pendingIds(int limit);

        void markRunning(UUID submissionId);

        void markDone(UUID submissionId, GradingResult result);

        void markFailed(UUID submissionId, String error);
    }

    /** grading 暴露：sitting 回写分数。 */
    public interface GradingOutcome {
        void apply(UUID submissionId, GradingResult result);
    }

    /** paper 暴露。 */
    public interface PaperCatalog {
        PaperSnapshot load(UUID paperId);
    }

    /** classroom 暴露：某学生所在的班级 id。 */
    public interface ClassMembership {
        List<UUID> classIdsOf(UUID studentId);
    }

    /** identity 暴露。 */
    public interface NotificationPort {
        void notifyScoresPublished(UUID offeringId);
    }
}
