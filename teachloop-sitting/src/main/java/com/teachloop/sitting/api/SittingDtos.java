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
    public record ClassSummaryView(String classId, long expected, long submitted, long onTime, long late,
                                   long rejected, BigDecimal onTimeRate, BigDecimal averageScore) {
    }

    public record ProgressView(int total, long done, long running, long pending, long failed) {
    }
}
