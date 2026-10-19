package com.teachloop.sitting.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "objective_answers")
public class ObjectiveAnswer {

    @Id
    private UUID id;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false, columnDefinition = "text")
    private String answer;

    protected ObjectiveAnswer() {
    }

    public ObjectiveAnswer(UUID id, UUID submissionId, UUID itemId, int number, String answer) {
        this.id = id;
        this.submissionId = submissionId;
        this.itemId = itemId;
        this.number = number;
        this.answer = answer;
    }

    public UUID getId() { return id; }
    public UUID getSubmissionId() { return submissionId; }
    public UUID getItemId() { return itemId; }
    public int getNumber() { return number; }
    public String getAnswer() { return answer; }
}
