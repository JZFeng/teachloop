package com.teachloop.classroom.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "classes")
public class ClassEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;

    @Column(name = "invite_code", nullable = false, unique = true)
    private String inviteCode;

    @Column(nullable = false)
    private boolean archived = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ClassEntity() {
    }

    public ClassEntity(UUID id, String name, UUID teacherId, String inviteCode, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.teacherId = teacherId;
        this.inviteCode = inviteCode;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getTeacherId() { return teacherId; }
    public String getInviteCode() { return inviteCode; }
    public boolean isArchived() { return archived; }

    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
    public void setArchived(boolean archived) { this.archived = archived; }
}
