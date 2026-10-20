package com.teachloop.paper.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "objective_items")
public class ObjectiveItem {

    @Id
    private UUID id;

    @Column(name = "paper_id", nullable = false)
    private UUID paperId;

    @Column(name = "paper_section_id", nullable = false)
    private UUID paperSectionId;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false)
    private QuestionType questionType = QuestionType.SINGLE_CHOICE;

    @Column(columnDefinition = "text")
    private String stem;

    @Column(columnDefinition = "text")
    private String options;

    @Column(name = "answer_key", columnDefinition = "text")
    private String answerKey;

    @Column(nullable = false)
    private BigDecimal points;

    @Column(name = "explanation_state", nullable = false)
    private String explanationState = "none";

    /** T196：仅 MULTIPLE_CHOICE 生效；默认 ALL_OR_NOTHING。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scoring_strategy", nullable = false)
    private ScoringStrategy scoringStrategy = ScoringStrategy.ALL_OR_NOTHING;

    protected ObjectiveItem() {
    }

    public ObjectiveItem(UUID id, UUID paperId, UUID paperSectionId, int number, int position,
                         QuestionType questionType, String stem, String options, String answerKey, BigDecimal points) {
        this.id = id;
        this.paperId = paperId;
        this.paperSectionId = paperSectionId;
        this.number = number;
        this.position = position;
        this.questionType = questionType;
        this.stem = stem;
        this.options = options;
        this.answerKey = answerKey;
        this.points = points;
    }

    public UUID getId() { return id; }
    public UUID getPaperId() { return paperId; }
    public UUID getPaperSectionId() { return paperSectionId; }
    public int getNumber() { return number; }
    public int getPosition() { return position; }
    public QuestionType getQuestionType() { return questionType; }
    public String getStem() { return stem; }
    public String getOptions() { return options; }
    public String getAnswerKey() { return answerKey; }
    public BigDecimal getPoints() { return points; }
    public String getExplanationState() { return explanationState; }

    public void setExplanationState(String state) { this.explanationState = state; }
    public ScoringStrategy getScoringStrategy() { return scoringStrategy; }
    public void setScoringStrategy(ScoringStrategy strategy) { this.scoringStrategy = strategy; }
    public void setQuestionType(QuestionType type) { this.questionType = type; }
    public void setAnswerKey(String key) { this.answerKey = key; }
    public void setStem(String stem) { this.stem = stem; }
    public void setOptions(String options) { this.options = options; }
    public void setPoints(BigDecimal points) { this.points = points; }
}
