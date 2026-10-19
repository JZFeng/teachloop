package com.teachloop.classroom.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassMemberRepository extends JpaRepository<ClassMember, UUID> {
    List<ClassMember> findByClassId(UUID classId);

    List<ClassMember> findByStudentId(UUID studentId);

    boolean existsByClassIdAndStudentId(UUID classId, UUID studentId);

    long countByClassId(UUID classId);
}
