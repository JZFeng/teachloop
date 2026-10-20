package com.teachloop.sitting.api;

import java.math.BigDecimal;
import java.util.List;

public final class SittingDtos {

    private SittingDtos() {
    }

    /** 按班覆盖（T198 / AC-26.4）：不填即继承布置级设置。 */
    public record ClassAssignment(String classId, String dueAtOverride, Integer durationOverride) {
    }

    public record CreateOffering(String paperId, String kind, String startAt, String dueAt,
                                 Integer durationMinutes, String timingMode, List<String> classIds,
                                 List<ClassAssignment> classes, Integer maxAttempts, Integer graceHours,
                                 String feedbackLanguage, Boolean showPeerOverview, Boolean publishScores,
                                 Boolean publish, String gradingPromptTemplate) {
    }

    public record OfferingClassView(String classId, String dueAtOverride, Integer durationOverride,
                                    long memberCount) {
    }

    public record OfferingView(String id, String paperId, String paperTitle, int paperVersion, String kind,
                               String timingMode, String status, String startAt, String dueAt,
                               Integer durationMinutes, int effectiveDurationMinutes, int graceHours,
                               int maxAttempts, String feedbackLanguage, boolean showPeerOverview,
                               boolean publishScores, int classCount, int submissionCount,
                               List<OfferingClassView> classes) {
    }

    public record AnswerInput(int number, String answer) {
    }

    public record SubmitRequest(String contentText, List<AnswerInput> answers) {
    }

    public record SessionView(String submissionId, int attemptNo, String startedAt, String deadlineAt,
                              long remainingSeconds, String serverNow, boolean started, boolean submitted,
                              boolean autoSubmitted, boolean locked, String timingMode) {
    }

    public record AnswerSaveResponse(String submissionId, BigDecimal objectiveScore, long remainingSeconds,
                                     String serverTime) {
    }

    public record SubmitResponse(String submissionId, int attemptNo, BigDecimal objectiveScore,
                                 BigDecimal totalPoints, boolean autoSubmitted, long lateBySeconds,
                                 String gradingState) {
    }

    /** 考试入口页（考前须知，T200 / AC-21.16）。 */
    public record EntryView(String offeringId, String paperTitle, int itemCount, int totalPoints,
                            int durationMinutes, String timingMode, String startAt, String dueAt,
                            String serverNow, boolean started, boolean canStart, long remainingSeconds,
                            String deadlineAt, boolean submitted, boolean autoSubmitted, boolean locked,
                            String notice, List<String> notes) {
    }

    /** 学生看到的一次作答尝试（含拒收理由，T202 / AC-8.6）。 */
    public record AttemptView(int attemptNo, String submittedAt, boolean autoSubmitted, long lateBySeconds,
                              String rejectedAt, String rejectReason, String gradingState,
                              BigDecimal objectiveScore, BigDecimal subjectiveScore, BigDecimal totalScore,
                              boolean active) {
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

    public record ExamPaperItemView(int number, String questionType, BigDecimal points, String stem,
                                    String options, int sectionPosition, String sectionTitle) {
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

    public record MyFeedbackView(String offeringId, String paperTitle, BigDecimal totalPoints,
                                 boolean feedbackPublished, String feedbackMessage,
                                 AttemptView latestAttempt, List<FeedbackWrongItem> wrongItems,
                                 FeedbackReviewNote review, FeedbackOverview overview) {
    }

    /** 手动派发结果（T092）：runId 幂等，firstDispatch 区分「刚创建 run」与「本来就派过」。 */
    public record GradingDispatchView(String offeringId, String runId, boolean firstDispatch, int enqueued) {
    }
}
