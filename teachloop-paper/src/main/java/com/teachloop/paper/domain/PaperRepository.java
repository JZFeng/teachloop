package com.teachloop.paper.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperRepository extends JpaRepository<Paper, UUID> {
    List<Paper> findByAuthorIdOrderByCreatedAtDesc(UUID authorId);
}
