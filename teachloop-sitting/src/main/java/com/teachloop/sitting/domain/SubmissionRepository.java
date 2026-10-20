package com.teachloop.sitting.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    List<Submission> findByOfferingIdOrderByCreatedAtDesc(UUID offeringId);

    Optional<Submission> findByOfferingIdAndStudentIdAndActiveTrue(UUID offeringId, UUID studentId);

    List<Submission> findByOfferingIdAndStudentIdOrderByAttemptNoDesc(UUID offeringId, UUID studentId);

    /** 到点还没交卷的草稿（T199）。 */
    @Query("select s.id from Submission s where s.submittedAt is null and s.active = true "
            + "and s.deadlineAt is not null and s.deadlineAt < :now")
    List<UUID> findExpiredDraftIds(Instant now);

    /** 条件更新保证重复扫描无副作用：已交卷的行不会被覆盖。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update submissions set submitted_at = :now, auto_submitted = true, "
            + "grading_state = :state, late_by_seconds = 0 "
            + "where id = :id and submitted_at is null", nativeQuery = true)
    int markAutoSubmitted(UUID id, Instant now, String state);

    long countByOfferingIdAndGradingState(UUID offeringId, GradingState state);

    @Query("select s.id from Submission s where s.gradingState = 'PENDING' order by s.createdAt")
    List<UUID> findPendingIds();

    /** 领取工作项：条件更新就是并发保证。 */
    @Modifying
    @Query(value = "update submissions set grading_state = 'RUNNING', grading_attempts = grading_attempts + 1, grading_started_at = now() where id = :id and grading_state = 'PENDING'", nativeQuery = true)
    int claim(UUID id);
}
