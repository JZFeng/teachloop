package com.teachloop.paper.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/** 主观题评分项（rubric 的结构化部分，用于校验 AI 打分）。 */
@Entity
@Table(name = "section_criteria")
public class SectionCriteria {

    @Id
    private UUID id;

    @Column(name = "paper_id", nullable = false)
    private UUID paperId;

    @Column(name = "paper_section_id", nullable = false)
    private UUID paperSectionId;

    @Column(nullable = false)
    private String name;

    @Column(name = "max_points", nullable = false)
    private BigDecimal maxPoints;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private int position;

    protected SectionCriteria() {
    }

    public SectionCriteria(UUID id, UUID paperId, UUID paperSectionId, String name, BigDecimal maxPoints,
                           String description, int position) {
        this.id = id;
        this.paperId = paperId;
        this.paperSectionId = paperSectionId;
        this.name = name;
        this.maxPoints = maxPoints;
        this.description = description;
        this.position = position;
    }

    public UUID getId() { return id; }
    public UUID getPaperSectionId() { return paperSectionId; }
    public String getName() { return name; }
    public BigDecimal getMaxPoints() { return maxPoints; }
    public String getDescription() { return description; }
    public int getPosition() { return position; }
}
