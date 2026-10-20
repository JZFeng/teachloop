package com.teachloop.paper.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObjectiveItemRepository extends JpaRepository<ObjectiveItem, UUID> {
    java.util.Optional<ObjectiveItem> findByPaperIdAndNumber(UUID paperId, int number);
    List<ObjectiveItem> findByPaperIdOrderByNumber(UUID paperId);

    List<ObjectiveItem> findByPaperSectionIdOrderByPosition(UUID sectionId);
}
