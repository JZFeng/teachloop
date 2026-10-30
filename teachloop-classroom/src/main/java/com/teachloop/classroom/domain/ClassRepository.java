package com.teachloop.classroom.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClassRepository extends JpaRepository<ClassEntity, UUID> {
    List<ClassEntity> findByTeacherIdOrderByCreatedAtDesc(UUID teacherId);

    /** T329：这批班级里哪些是这位老师的（报表归属校验）。 */
    @Query("select c.id from ClassEntity c where c.teacherId = :teacherId and c.id in :ids")
    List<UUID> findOwnedIds(java.util.Collection<UUID> ids, UUID teacherId);

    Optional<ClassEntity> findByInviteCode(String inviteCode);

    /**
     * 学生视角的班级列表（012 / AC-96.3）。
     *
     * <p>只取 {@code ACTIVE} 成员：退班后这个班从列表里消失；但已结课（{@code archived=true}）
     * 的班仍返回——学生要能回看历史作业与成绩（AC-98.2）。
     */
    @Query("select c from ClassEntity c, ClassMember m where m.classId = c.id "
            + "and m.studentId = :studentId "
            + "and m.status = com.teachloop.classroom.domain.MemberStatus.ACTIVE "
            + "order by c.createdAt desc")
    List<ClassEntity> findForStudent(UUID studentId);
}
