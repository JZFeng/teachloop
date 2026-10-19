package com.teachloop.paper.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SectionCriteriaRepository extends JpaRepository<SectionCriteria, UUID> {
    List<SectionCriteria> findByPaperIdOrderByPosition(UUID paperId);

    List<SectionCriteria> findByPaperSectionIdOrderByPosition(UUID sectionId);
}
