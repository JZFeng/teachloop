package com.teachloop.classroom.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "class_members")
public class ClassMember {

    @Id
    private UUID id;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected ClassMember() {
    }

    public ClassMember(UUID id, UUID classId, UUID studentId, Instant joinedAt) {
        this.id = id;
        this.classId = classId;
        this.studentId = studentId;
        this.joinedAt = joinedAt;
    }

    public UUID getId() { return id; }
    public UUID getClassId() { return classId; }
    public UUID getStudentId() { return studentId; }
}
