package com.teachloop.shared.port;

import java.math.BigDecimal;
import java.time.Instant;
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
                           int sectionPosition, String sectionTitle, String scoringStrategy) {

        /** 兼容既有测试与调用方：结构字段缺省。 */
        public ItemSpec(UUID itemId, int number, String questionType, String answerKey,
                        BigDecimal points, String stem) {
            this(itemId, number, questionType, answerKey, points, stem, null, 0, null, "ALL_OR_NOTHING");
        }

        public ItemSpec withAnswerKey(String key) {
            return new ItemSpec(itemId, number, questionType, key, points, stem, options,
                    sectionPosition, sectionTitle, scoringStrategy);
        }

        public ItemSpec(UUID itemId, int number, String questionType, String answerKey,
                        BigDecimal points, String stem, String options,
                        int sectionPosition, String sectionTitle) {
            this(itemId, number, questionType, answerKey, points, stem, options,
                    sectionPosition, sectionTitle, "ALL_OR_NOTHING");
        }
    }

    /** 学生的某一道题的作答。 */
    public record AnswerRow(UUID itemId, int number, String answer) {
    }

    /** 试卷只读快照（布置时固化，不回查活表）；paperVersion 随快照一起落库，便于追溯。 */
    public record PaperSnapshot(UUID paperId, int paperVersion, String title, int totalPoints,
                                int durationMinutes, List<ItemSpec> items, String rubricMarkdown) {

        public PaperSnapshot withItems(List<ItemSpec> newItems) {
            return new PaperSnapshot(paperId, paperVersion, title, totalPoints, durationMinutes,
                    newItems, rubricMarkdown);
        }
    }

    /**
     * 阅卷需要的一切，一次取齐（胖消息、瘦 Worker）。
     *
     * <p>contentHtml 是写入时已消毒的富文本原文（T250）：AI prompt 只用 contentText，
     * 教师复核页要按原格式回显，所以两个都带。
     */
    public record WorkItem(UUID submissionId, UUID offeringId, UUID studentId, String studentName,
                           String inputKind, String contentText, String contentHtml,
                           List<AnswerRow> answers, List<ItemSpec> items, String rubricMarkdown,
                           String gradingPrompt, String feedbackLanguage) {
    }

    /**
     * 一份提交当前的分数与交卷状态（T243）。
     *
     * <p>老师可以完全不依赖 AI 判分：复核工作台需要读到「当前客观分 / 主观分」才能把人工分
     * 加在正确的基础上，而 {@link WorkItem} 刻意不带分数（阅卷只读作答，不做状态推进）。
     * 本记录只读，不包含学生身份以外的隐私字段。
     *
     * @param submittedAt 交卷时间；null 表示仍在作答（草稿），不允许判分
     * @param gradingState submissions.grading_state 的枚举名（QUEUED / PENDING / RUNNING / DONE / FAILED）
     */
    public record SubmissionScores(UUID submissionId, Instant submittedAt, BigDecimal objectiveTotal,
                                   BigDecimal subjectiveTotal, String gradingState) {
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

        /**
         * T243：当前分数与交卷时间；提交不存在时抛 notFound。
         *
         * <p>只读，不改任何状态；人工判分的「当前实际分」以这里为准。
         */
        SubmissionScores scores(UUID submissionId);
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

    /** 管理员在初始设置里保存的 AI 配置；key 不随端口返回给业务方以外的接口。 */
    public record AiSettings(String provider, String baseUrl, String model, String apiKey,
                             BigDecimal temperature, boolean configured) {
    }

    public interface AiSettingsLookup {
        java.util.Optional<AiSettings> current();
    }

    /** T102：改标准答案后重算客观分。 */
    public record AnswerKey(UUID paperId, int number, String answerKey) {
    }

    public interface ObjectiveRecompute {
        int recomputePaper(UUID paperId, List<AnswerKey> keys);
    }

    /** 试卷被布置次数（T206）：sitting 只读聚合。 */
    public interface PaperUsage {
        int assignmentCount(UUID paperId);
    }

    /** 班级成员提交数（T042）：sitting 只读聚合，classroom 负责展示。 */
    public record ClassSubmissionCount(UUID studentId, int count) {
    }

    public interface ClassSubmissionStats {
        List<ClassSubmissionCount> submissionCounts(UUID classId);
    }

    /** 复核结论的只读投影：学生反馈用，不暴露教师身份。 */
    public record ReviewNote(String status, String comment, String strengths, String suggestions,
                             String createdAt, String publishedAt) {
    }

    /** 生成解析所需的一道错题（题目级，不含学生身份）。 */
    public record ExplanationItem(java.util.UUID itemId, int number, String questionType, String stem,
                                  String options, String answerKey, int wrongCount, String language) {
    }

    /** sitting 暴露：某场考试需要生成解析的错题（按答错人次降序）。 */
    public interface ExplanationWorkQueue {
        java.util.List<ExplanationItem> wrongItemsOf(java.util.UUID offeringId, int limit);
    }

    /** grading 暴露：错题解析（题目级共享，按语言与 prompt 版本缓存）。 */
    public interface ExplanationCatalog {
        java.util.Optional<String> explanationOf(UUID itemId, String language);
    }

    /** grading 暴露：学生反馈读复核结论（只读）。 */
    public interface ReviewFeedback {
        java.util.Optional<ReviewNote> noteOf(UUID submissionId);
    }

    /** identity 暴露：只读显示名，用于教师端提交表；不暴露联系方式。 */
    public interface StudentDirectory {
        java.util.Map<UUID, String> displayNames(java.util.Collection<UUID> userIds);
    }

    /** 一条站内通知：接收方与标题正文由调用方给出，identity 只负责存储与投递。 */
    public record NotificationMessage(UUID recipientId, String kind, String targetType, String targetId,
                                      String title, String body) {
    }

    /** identity 暴露；接收方与联系方式不外出。 */
    public interface NotificationPort {
        void notifyOfferingPublished(UUID offeringId);

        void notifyScoresPublished(UUID offeringId);

        /** 批量写入站内通知；重复调用不保证去重（由调用方保证语义）。 */
        default void notify(java.util.List<NotificationMessage> messages) {
        }
    }
}
