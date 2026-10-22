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

    /**
     * 学生的某一道题的作答。
     *
     * <p>{@code correct} 是提交时落库的判定结果（{@code objective_answers.correct}），
     * {@code awarded} 是按快照标准答案算出的本题实得分；两者只对可自动判分的题有值，
     * 作文/主观题一律为 null。复核页据此把答对/答错/部分对分色显示。
     */
    public record AnswerRow(UUID itemId, int number, String answer,
                            Boolean correct, BigDecimal awarded) {

        /** 兼容既有调用：只关心作答内容时，对错与得分留空。 */
        public AnswerRow(UUID itemId, int number, String answer) {
            this(itemId, number, answer, null, null);
        }
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
                           String gradingPrompt, String feedbackLanguage, UUID createdBy, String anchors) {

        /** 兼容既有测试与调用方：没有作业创建者/锚点时按全局 Key、无锚点处理。 */
        public WorkItem(UUID submissionId, UUID offeringId, UUID studentId, String studentName,
                        String inputKind, String contentText, String contentHtml,
                        List<AnswerRow> answers, List<ItemSpec> items, String rubricMarkdown,
                        String gradingPrompt, String feedbackLanguage) {
            this(submissionId, offeringId, studentId, studentName, inputKind, contentText, contentHtml,
                    answers, items, rubricMarkdown, gradingPrompt, feedbackLanguage, null, null);
        }

        public WorkItem(UUID submissionId, UUID offeringId, UUID studentId, String studentName,
                        String inputKind, String contentText, String contentHtml,
                        List<AnswerRow> answers, List<ItemSpec> items, String rubricMarkdown,
                        String gradingPrompt, String feedbackLanguage, UUID createdBy) {
            this(submissionId, offeringId, studentId, studentName, inputKind, contentText, contentHtml,
                    answers, items, rubricMarkdown, gradingPrompt, feedbackLanguage, createdBy, null);
        }
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
                                List<ItemExplanation> explanations,
                                String overall, String strength, String suggestion,
                                String model, String promptVersion, String keySource) {

        /** 兼容旧调用：只带合并后的 feedback。 */
        public GradingResult(BigDecimal objectivePoints, BigDecimal subjectivePoints,
                             List<CriterionScore> criteria, String feedback,
                             List<ItemExplanation> explanations) {
            this(objectivePoints, subjectivePoints, criteria, feedback, explanations,
                    null, null, null, null, null, null);
        }

        /** 兼容 v2 校验结果：不带模型/Key 溯源。 */
        public GradingResult(BigDecimal objectivePoints, BigDecimal subjectivePoints,
                             List<CriterionScore> criteria, String feedback,
                             List<ItemExplanation> explanations,
                             String overall, String strength, String suggestion) {
            this(objectivePoints, subjectivePoints, criteria, feedback, explanations,
                    overall, strength, suggestion, null, null, null);
        }
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

        /** T329：这批班级里哪些属于该教师（报表按班级取数时的归属校验）。 */
        java.util.Set<UUID> classIdsOwnedBy(java.util.Collection<UUID> classIds, UUID teacherId);
    }

    /** 管理员/教师保存的 AI 配置；key 不随端口返回给业务方以外的接口。 */
    public record AiSettings(String provider, String baseUrl, String model, String apiKey,
                             BigDecimal temperature, boolean configured, String source) {

        public AiSettings(String provider, String baseUrl, String model, String apiKey,
                          BigDecimal temperature, boolean configured) {
            this(provider, baseUrl, model, apiKey, temperature, configured, configured ? "global" : "none");
        }
    }

    public interface AiSettingsLookup {
        java.util.Optional<AiSettings> current();

        /** D-16：本人 Key 优先，缺失回退全局；返回的 source 是 user / global。 */
        java.util.Optional<AiSettings> resolve(UUID teacherId);
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

    /**
     * 归属查询（T329）：阅卷模块不读 sitting 的表，只能问「这些提交归不归这位老师」。
     *
     * <p>没有这个端口，复核队列、复核详情与批量通过就只能是「同角色即可看/可改」，
     * 教师首页「待复核」会混进别的老师的提交，知道 id 就能改别人的分数。
     */
    public interface OfferingOwnership {
        /** 在一批提交里挑出属于该教师自己布置的（一次查询）。 */
        java.util.Set<java.util.UUID> ownedSubmissionIds(java.util.Collection<java.util.UUID> submissionIds,
                                                         java.util.UUID teacherId);

        /** 在一批布置里挑出该教师自己布置的（报表按 id 列表取数时用）。 */
        java.util.Set<java.util.UUID> ownedOfferingIds(java.util.Collection<java.util.UUID> offeringIds,
                                                       java.util.UUID teacherId);

        /**
         * 在一批提交里挑出仍然 active 的（学生重交后，旧 attempt 变 inactive）。
         * 复核队列用它去掉被取代的旧草稿：同一场同一个学生只该复核最新一版。
         */
        java.util.Set<java.util.UUID> activeSubmissionIds(java.util.Collection<java.util.UUID> submissionIds);
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

    /** identity 暴露：只读显示名/界面语言，用于教师端提交表与反馈语言；不暴露联系方式。 */
    public interface StudentDirectory {
        java.util.Map<UUID, String> displayNames(java.util.Collection<UUID> userIds);

        /** 学生的界面语言（`zh-CN` / `en`）；缺失时由调用方回退。 */
        java.util.Map<UUID, String> locales(java.util.Collection<UUID> userIds);
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
