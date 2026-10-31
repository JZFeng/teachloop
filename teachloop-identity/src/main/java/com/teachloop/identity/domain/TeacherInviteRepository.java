package com.teachloop.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeacherInviteRepository extends JpaRepository<TeacherInvite, UUID> {
    Optional<TeacherInvite> findByCode(String code);

    /** AC-3.11：管理端列表，新生成的码排在最前。 */
    List<TeacherInvite> findAllByOrderByCreatedAtDesc();
}
