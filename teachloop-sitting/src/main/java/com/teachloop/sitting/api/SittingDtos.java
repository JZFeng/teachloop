package com.teachloop.sitting.api;

import java.math.BigDecimal;
import java.util.List;

public final class SittingDtos {

    private SittingDtos() {
    }

    /** 按班覆盖（T198 / AC-26.4）：不填即继承布置级设置。 */
    public record ClassAssignment(String classId, String dueAtOverride, Integer durationOverride) {
    }

    /**
     * 一次布置的创建请求（T201 / D-14）。
     *
     * <p>两种内容来源：{@code paperId} 非空 → 考试（快照取自试卷）；{@code paperId} 为空 →
     * 作业（内容来自 {@code title} / {@code instructions} / {@code totalPoints} / {@code rubricMarkdown}）。
     * {@code kind} 只为兼容旧客户端保留——必须与内容来源一致，否则 400，不再由用户选择。
     */
    public record CreateOffering(String paperId, String kind, String startAt, String dueAt,
                                 Integer durationMinutes, String timingMode, List<String> classIds,
                                 List<ClassAssignment> classes, Integer maxAttempts, Integer graceHours,
                                 String feedbackLanguage, Boolean showPeerOverview, Boolean publishScores,
                                 Boolean publish, String gradingPromptTemplate,
                                 String title, String instructions, Integer totalPoints,
                                 String rubricMarkdown, String anchorExamples) {
    }

    public record OfferingClassView(String classId, String dueAtOverride, Integer durationOverride,
                                    long memberCount) {
    }

    /** {@code paperId} 只有考试才有；独立作业（D-14）为 null，标题仍取快照 title。 */
    public record OfferingView(String id, String paperId, String paperTitle, int paperVersion, String kind,
                               String timingMode, String status, String startAt, String dueAt,
                               Integer durationMinutes, int effectiveDurationMinutes, int graceHours,
                               int maxAttempts, String feedbackLanguage, boolean showPeerOverview,
                               boolean publishScores, int classCount, int submissionCount,
                               List<OfferingClassView> classes) {
    }

    public record AnswerInput(int number, String answer) {
    }

    /** T060：contentHtml 优先（会消毒并提取 contentText）；没有富文本时按纯文本 contentText 处理。 */
    public record SubmitRequest(String contentText, String contentHtml, List<AnswerInput> answers) {
    }

    public record SessionView(String submissionId, int attemptNo, String startedAt, String deadlineAt,
                              long remainingSeconds, String serverNow, boolean started, boolean submitted,
                              boolean autoSubmitted, boolean locked, String timingMode) {
    }

    public record AnswerSaveResponse(String submissionId, BigDecimal objectiveScore, long remainingSeconds,
                                     String serverTime) {
    }

    /** T061：文件提交额外返回 unreadable（解析失败不评分）与附件原名，便于前端提示。 */
    public record SubmitResponse(String submissionId, int attemptNo, BigDecimal objectiveScore,
                                 BigDecimal totalPoints, boolean autoSubmitted, long lateBySeconds,
                                 String gradingState, boolean unreadable, String attachmentName) {
    }

    /** 考试入口页（考前须知，T200 / AC-21.16）。 */
    public record EntryView(String offeringId, String paperTitle, int itemCount, int totalPoints,
                            int durationMinutes, String timingMode, String startAt, String dueAt,
                            String serverNow, boolean started, boolean canStart, long remainingSeconds,
                            String deadlineAt, boolean submitted, boolean autoSubmitted, boolean locked,
                            String notice, List<String> notes, String rubric) {
    }

    /**
     * 学生看到的一次作答尝试（含拒收理由，T202 / AC-8.6）。
     *
     * <p>contentHtml / contentText 是这名学生自己提交的正文（T248）：作业页提交后直接回显，
     * 不用再点按钮跳到反馈页。contentHtml 在写入时已消毒，回显安全；没有富文本时为 null。
     */
    public record AttemptView(int attemptNo, String submittedAt, boolean autoSubmitted, long lateBySeconds,
                              String rejectedAt, String rejectReason, String gradingState,
                              BigDecimal objectiveScore, BigDecimal subjectiveScore, BigDecimal totalScore,
                              boolean active, String submissionId, String attachmentName,
                              String contentHtml, String contentText) {
    }

    public record MyAttemptsView(String offeringId, int attemptsUsed, int maxAttempts, boolean canRetry,
                                 Integer activeAttemptNo, List<AttemptView> attempts) {
    }

    public record RejectRequest(String reason) {
    }

    /** 按班统计：按时提交率必须与迟交份数分开（AC-8.2 / AC-26.10）。 */
    public record UnsubmittedView(String studentId, String studentName) {
    }

    /** 分数分布（T191）：按「得分 / 满分」的百分比分桶，便于跨卷比较。 */
    public record ScoreBucketView(String label, long count) {
    }

    /** 逐题正确率（T191）：只统计客观题，按题号升序。 */
    public record ItemAccuracyView(int number, long answered, long correct, BigDecimal correctRate) {
    }

    public record ClassSummaryView(String classId, long expected, long submitted, long onTime, long late,
                                   long rejected, BigDecimal onTimeRate, BigDecimal averageScore,
                                   List<UnsubmittedView> unsubmitted, List<ScoreBucketView> distribution,
                                   List<ItemAccuracyView> itemAccuracy) {
    }

    public record ProgressView(int total, long done, long running, long pending, long failed) {
    }

    /** 学生端试卷内容（T198 快照）：绝不包含标准答案与解析（AC-21.9）。 */
    public record ExamPaperView(String offeringId, String title, int totalPoints, int durationMinutes,
                                List<ExamPaperItemView> items) {
    }

    /**
     * 学生端逐题视图。{@code groupKey}/{@code material}/{@code questionStem}/{@code blankIndex}
     * 是 T321 的展示分组元数据：同一 {@code groupKey} 的题共用材料，材料只在组内第一条带上；
     * {@code blankIndex} 表示该题是材料里的第几个空。分组不可靠时全部为空，前端按原样渲染。
     */
    public record ExamPaperItemView(int number, String questionType, BigDecimal points, String stem,
                                    String options, int sectionPosition, String sectionTitle,
                                    String groupKey, String material, String questionStem, Integer blankIndex) {

        /** 兼容既有测试与调用方：不带分组元数据。 */
        public ExamPaperItemView(int number, String questionType, BigDecimal points, String stem,
                                 String options, int sectionPosition, String sectionTitle) {
            this(number, questionType, points, stem, options, sectionPosition, sectionTitle,
                    null, null, null, null);
        }
    }

    /** 教师端逐份提交表（T182）：姓名经 StudentDirectory 端口取，不跨模块查 users。 */
    public record OfferingSubmissionView(String submissionId, String studentId, String studentName,
                                         int attemptNo, String submittedAt, boolean autoSubmitted,
                                         long lateBySeconds, String rejectedAt, String rejectReason,
                                         String gradingState, BigDecimal objectiveScore,
                                         BigDecimal subjectiveScore, BigDecimal totalScore, boolean active) {
    }

    /** 学生反馈里的错题（T111 / T194）：已发布反馈才返回标准答案。 */
    public record FeedbackWrongItem(int number, String questionType, BigDecimal points, String stem,
                                    String given, String correctAnswer, String explanation) {
    }

    public record FeedbackReviewNote(String status, String comment, String strengths, String suggestions,
                                     String createdAt) {
    }

    /** 班级概览（T112 / T113）：他人分数仅在发布且允许同学概览时可见；自己的分始终可见。 */
    public record FeedbackOverview(long expected, long submitted, BigDecimal myScore,
                                   BigDecimal classAverage, boolean peersVisible) {
    }

    /**
     * 学生反馈详情。
     *
     * @param objectiveMax 本卷客观题满分；0 表示整卷没有客观题（前端据此隐藏「客观题」一行）
     */
    public record MyFeedbackView(String offeringId, String paperTitle, BigDecimal totalPoints,
                                 boolean feedbackPublished, String feedbackMessage,
                                 AttemptView latestAttempt, List<FeedbackWrongItem> wrongItems,
                                 FeedbackReviewNote review, FeedbackOverview overview,
                                 BigDecimal objectiveMax) {
    }

    /** 手动派发结果（T092）：runId 幂等，firstDispatch 区分「刚创建 run」与「本来就派过」。 */
    public record GradingDispatchView(String offeringId, String runId, boolean firstDispatch, int enqueued) {
    }

    /** T252：单份「让 AI 先批」的回执。 */
    public record SubmissionDispatchView(String offeringId, String submissionId, String runId) {
    }
}
