package com.teachloop.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    long countByRole(UserRole role);

    List<User> findByRole(UserRole role);

    Optional<User> findByWechatOpenId(String openId);
}
