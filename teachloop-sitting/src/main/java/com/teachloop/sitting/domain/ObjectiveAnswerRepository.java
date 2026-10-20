package com.teachloop.sitting.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ObjectiveAnswerRepository extends JpaRepository<ObjectiveAnswer, UUID> {
    List<ObjectiveAnswer> findBySubmissionId(UUID submissionId);

    /** 自动保存是「整份替换」：先删后插，避免残留旧选项。 */
    @Modifying
    @Query("delete from ObjectiveAnswer a where a.submissionId = :submissionId")
    void deleteBySubmissionId(UUID submissionId);
}
