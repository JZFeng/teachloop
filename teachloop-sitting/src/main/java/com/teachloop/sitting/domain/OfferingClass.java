package com.teachloop.sitting.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "offering_classes")
public class OfferingClass {

    @Id
    private UUID id;

    @Column(name = "offering_id", nullable = false)
    private UUID offeringId;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "due_at_override")
    private java.time.Instant dueAtOverride;

    @Column(name = "duration_override")
    private Integer durationOverride;

    protected OfferingClass() {
    }

    public OfferingClass(UUID id, UUID offeringId, UUID classId) {
        this.id = id;
        this.offeringId = offeringId;
        this.classId = classId;
    }

    public UUID getId() { return id; }
    public UUID getOfferingId() { return offeringId; }
    public UUID getClassId() { return classId; }
    public java.time.Instant getDueAtOverride() { return dueAtOverride; }
    public Integer getDurationOverride() { return durationOverride; }
}
