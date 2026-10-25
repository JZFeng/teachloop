package com.teachloop.paper.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperContentSnapshotRepository extends JpaRepository<PaperContentSnapshot, UUID> {
    /** 最新在前；写入后按这个顺序裁到 3 条。 */
    List<PaperContentSnapshot> findByPaperIdOrderByCreatedAtDesc(UUID paperId);
}
