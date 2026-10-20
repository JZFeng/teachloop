package com.teachloop.identity.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeacherInviteRepository extends JpaRepository<TeacherInvite, UUID> {
    Optional<TeacherInvite> findByCode(String code);
}
