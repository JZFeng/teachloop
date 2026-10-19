package com.teachloop.sitting.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObjectiveAnswerRepository extends JpaRepository<ObjectiveAnswer, UUID> {
    List<ObjectiveAnswer> findBySubmissionId(UUID submissionId);
}
