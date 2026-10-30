package com.teachloop.classroom.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 班级成员（012 / FR-214）。
 *
 * <p>一行代表一段「加入关系」，用状态表达它是否还在生效：{@link MemberStatus#LEFT} 的行照样留着，
 * 重新加入时复用同一行（{@code joined_at} 永远是首次加入时间，{@code rejoined_at} 记最近一次重入）。
 */
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemberStatus status = MemberStatus.ACTIVE;

    /** 最近一次离开（退出 / 移出）的时间；重入不清空，保留历史。 */
    @Column(name = "left_at")
    private Instant leftAt;

    /** 最近一次重新加入的时间；首次加入为 null。 */
    @Column(name = "rejoined_at")
    private Instant rejoinedAt;

    protected ClassMember() {
    }

    public ClassMember(UUID id, UUID classId, UUID studentId, Instant joinedAt) {
        this.id = id;
        this.classId = classId;
        this.studentId = studentId;
        this.joinedAt = joinedAt;
        this.status = MemberStatus.ACTIVE;
    }

    public UUID getId() { return id; }
    public UUID getClassId() { return classId; }
    public UUID getStudentId() { return studentId; }
    public Instant getJoinedAt() { return joinedAt; }
    public MemberStatus getStatus() { return status; }
    public Instant getLeftAt() { return leftAt; }
    public Instant getRejoinedAt() { return rejoinedAt; }

    public boolean isActive() { return status == MemberStatus.ACTIVE; }

    /** 退出 / 移出：只写状态与时间，不删除行（FR-215）。 */
    public void leave(Instant now) {
        this.status = MemberStatus.LEFT;
        this.leftAt = now;
    }

    /**
     * 重新用邀请码加入：复用同一行（FR-217）。
     *
     * <p>{@code joined_at} 不改写——它是「首次加入」，报表与学籍口径都按它看；
     * 本次时间写进 {@code rejoined_at}。{@code left_at} 保留为上一次离开的时间。
     */
    public void rejoin(Instant now) {
        this.status = MemberStatus.ACTIVE;
        this.rejoinedAt = now;
    }
}
