package com.teachloop.shared.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 跨模块端口的数据契约：只有 record，没有实体，避免懒加载跨模块泄漏。 */
public final class Ports {

    private Ports() {
    }

    /**
     * 一道客观题或一道主观题的评分契约。
     *
     * <p><code>options</code> / <code>sectionPosition</code> / <code>sectionTitle</code> 是 T198 快照里
     * 供学生端渲染试卷用的结构字段；判分逻辑只关心 answerKey / points / questionType。
     */
    public record ItemSpec(UUID itemId, int number, String questionType, String answerKey,
                           BigDecimal points, String stem, String options,
                           int sectionPosition, String sectionTitle) {

        /** 兼容既有测试与调用方：结构字段缺省。 */
        public ItemSpec(UUID itemId, int number, String questionType, String answerKey,
                        BigDecimal points, String stem) {
            this(itemId, number, questionType, answerKey, points, stem, null, 0, null);
        }
    }

    /** 学生的某一道题的作答。 */
    public record AnswerRow(UUID itemId, int number, String answer) {
    }

    /** 试卷只读快照（布置时固化，不回查活表）；paperVersion 随快照一起落库，便于追溯。 */
    public record PaperSnapshot(UUID paperId, int paperVersion, String title, int totalPoints,
                                int durationMinutes, List<ItemSpec> items, String rubricMarkdown) {
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

    /** sitting 暴露的**查询**端口：阅卷只读作答，不做任何状态推进。 */
    public interface SittingLedger {
        WorkItem load(UUID submissionId);
    }

    /**
     * sitting 暴露的**命令**端口：领取与推进工作项。
     * 将来换成消息队列仍要求至少一次投递，所以每个方法都必须幂等。
     */
    public interface GradingWorkQueue {
        /** 待批改的工作项（PENDING），返回上限 limit 条。 */
        List<UUID> pendingIds(int limit);

        void markRunning(UUID submissionId);

        void markDone(UUID submissionId, GradingResult result);

        void markFailed(UUID submissionId, String error);
    }

    /**
     * grading 暴露的命令端口：到期派发一场考试，一个 offering 只产生一条 run。
     * 重复调用返回同一条 run 的 id（幂等）。
     */
    public interface GradingDispatch {
        UUID dispatch(UUID offeringId);
    }

    /** grading 暴露：sitting 回写分数。 */
    public interface GradingOutcome {
        void apply(UUID submissionId, GradingResult result);
    }

    /** paper 暴露。 */
    public interface PaperCatalog {
        PaperSnapshot load(UUID paperId);
    }

    /** classroom 暴露：班级成员关系（只读，不暴露学生资料）。 */
    public interface ClassMembership {
        List<UUID> classIdsOf(UUID studentId);

        List<UUID> membersOf(UUID classId);
    }

    /** 复核结论的只读投影：学生反馈用，不暴露教师身份。 */
    public record ReviewNote(String status, String comment, String strengths, String suggestions,
                             String createdAt) {
    }

    /** grading 暴露：学生反馈读复核结论（只读）。 */
    public interface ReviewFeedback {
        java.util.Optional<ReviewNote> noteOf(UUID submissionId);
    }

    /** identity 暴露：只读显示名，用于教师端提交表；不暴露联系方式。 */
    public interface StudentDirectory {
        java.util.Map<UUID, String> displayNames(java.util.Collection<UUID> userIds);
    }

    /** identity 暴露；接收方与联系方式不外出。 */
    public interface NotificationPort {
        void notifyOfferingPublished(UUID offeringId);

        void notifyScoresPublished(UUID offeringId);
    }
}
