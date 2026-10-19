package com.teachloop.sitting.api;

import java.math.BigDecimal;
import java.util.List;

public final class SittingDtos {

    private SittingDtos() {
    }

    public record CreateOffering(String paperId, String kind, String startAt, String dueAt,
                                 Integer durationMinutes, String timingMode, List<String> classIds,
                                 String feedbackLanguage) {
    }

    public record OfferingView(String id, String paperId, String kind, String dueAt, Integer durationMinutes,
                               String timingMode, String status, int classCount, int submissionCount,
                               boolean publishScores) {
    }

    public record AnswerInput(int number, String answer) {
    }

    public record SubmitRequest(String contentText, List<AnswerInput> answers) {
    }

    public record SubmitResponse(String submissionId, int attemptNo, BigDecimal objectiveScore, BigDecimal totalPoints,
                                 String gradingState) {
    }

    public record ProgressView(int total, long done, long running, long pending, long failed) {
    }
}
