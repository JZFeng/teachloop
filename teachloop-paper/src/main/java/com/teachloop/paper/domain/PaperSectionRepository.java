package com.teachloop.paper.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperSectionRepository extends JpaRepository<PaperSection, UUID> {
    List<PaperSection> findByPaperIdOrderByPosition(UUID paperId);
}
