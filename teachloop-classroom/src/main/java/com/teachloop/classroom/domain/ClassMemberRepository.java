package com.teachloop.classroom.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 成员查询（012 / FR-216）。
 *
 * <p>没有参数化的「全量成员」查询：默认口径一律是 {@link MemberStatus#ACTIVE}。
 * 需要历史成员的入口（老师勾选「含已离开」）显式调用带 {@code any-status} 的那两个方法，
 * 免得哪天有人顺手用 findAll 就把退班学生又算回在班人数里。
 */
public interface ClassMemberRepository extends JpaRepository<ClassMember, UUID> {

    /** 在班成员（默认口径）：班级列表的人数、发词收件人、提交参与人。 */
    List<ClassMember> findByClassIdAndStatusOrderByJoinedAtAsc(UUID classId, MemberStatus status);

    /** 含已离开（老师勾选「含已离开」时才用）。 */
    List<ClassMember> findByClassIdOrderByJoinedAtAsc(UUID classId);

    List<ClassMember> findByStudentIdAndStatus(UUID studentId, MemberStatus status);

    Optional<ClassMember> findByClassIdAndStudentId(UUID classId, UUID studentId);

    boolean existsByClassIdAndStudentIdAndStatus(UUID classId, UUID studentId, MemberStatus status);

    long countByClassIdAndStatus(UUID classId, MemberStatus status);
}
