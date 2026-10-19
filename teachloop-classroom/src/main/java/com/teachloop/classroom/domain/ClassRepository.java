package com.teachloop.classroom.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClassRepository extends JpaRepository<ClassEntity, UUID> {
    List<ClassEntity> findByTeacherIdOrderByCreatedAtDesc(UUID teacherId);

    Optional<ClassEntity> findByInviteCode(String inviteCode);

    @Query("select c from ClassEntity c, ClassMember m where m.classId = c.id and m.studentId = :studentId order by c.createdAt desc")
    List<ClassEntity> findForStudent(UUID studentId);
}
