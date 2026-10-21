package com.teachloop.paper.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "paper_sections")
public class PaperSection {

    @Id
    private UUID id;

    @Column(name = "paper_id", nullable = false)
    private UUID paperId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int position;

    @Column(name = "points_per_item")
    private BigDecimal pointsPerItem;

    protected PaperSection() {
    }

    public PaperSection(UUID id, UUID paperId, String title, int position, BigDecimal pointsPerItem) {
        this.id = id;
        this.paperId = paperId;
        this.title = title;
        this.position = position;
        this.pointsPerItem = pointsPerItem;
    }

    public UUID getId() { return id; }
    public UUID getPaperId() { return paperId; }
    public String getTitle() { return title; }
    public int getPosition() { return position; }
    public BigDecimal getPointsPerItem() { return pointsPerItem; }

    public void setTitle(String title) { this.title = title; }

    /** 只改「之后新增题项」的默认每题分值，不回溯改已有题项。 */
    public void setPointsPerItem(BigDecimal pointsPerItem) { this.pointsPerItem = pointsPerItem; }
}
