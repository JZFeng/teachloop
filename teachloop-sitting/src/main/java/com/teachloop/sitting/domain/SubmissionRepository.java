package com.teachloop.sitting.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    List<Submission> findByOfferingIdOrderByCreatedAtDesc(UUID offeringId);

    Optional<Submission> findByOfferingIdAndStudentIdAndActiveTrue(UUID offeringId, UUID studentId);

    long countByOfferingIdAndGradingState(UUID offeringId, GradingState state);

    @Query("select s.id from Submission s where s.gradingState = 'PENDING' order by s.createdAt")
    List<UUID> findPendingIds();

    /** 领取工作项：条件更新就是并发保证。 */
    @Modifying
    @Query(value = "update submissions set grading_state = 'RUNNING', grading_attempts = grading_attempts + 1, grading_started_at = now() where id = :id and grading_state = 'PENDING'", nativeQuery = true)
    int claim(UUID id);
}
