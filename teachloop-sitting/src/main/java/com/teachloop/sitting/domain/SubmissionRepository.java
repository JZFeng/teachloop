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

    /** T329：这批提交里哪些属于该老师自己布置的场次（阅卷归属校验用）。 */
    @Query("select s.id from Submission s, Offering o "
            + "where s.offeringId = o.id and o.createdBy = :teacherId and s.id in :ids")
    List<UUID> findOwnedIds(java.util.Collection<UUID> ids, UUID teacherId);

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

    @Query("select s.id from Submission s where s.gradingState = 'PENDING' order by s.createdAt")
    List<UUID> findPendingIds();

    /**
     * 领取工作项：条件更新就是并发保证。
     *
     * <p>时间用注入时钟而不是数据库 <code>now()</code>，这样固定时钟测试里「领取时间」与
     * 僵尸清扫的阈值判断是同一个时间轴。
     */
    @Modifying
    @Query(value = "update submissions set grading_state = 'RUNNING', "
            + "grading_attempts = grading_attempts + 1, grading_started_at = :now "
            + "where id = :id and grading_state = 'PENDING'", nativeQuery = true)
    int claim(UUID id, Instant now);

    /** T182：把这一场 FAILED 的提交退回 PENDING 重批；只动失败项，幂等。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update submissions set grading_state = 'PENDING', grading_error = null "
            + "where offering_id = :offeringId and grading_state = 'FAILED' "
            + "and submitted_at is not null and rejected_at is null", nativeQuery = true)
    int retryFailed(UUID offeringId);

    /** T064：开新 attempt 前先把旧的 active 置 false（唯一部分索引要求同一时刻只有一条 active）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Submission s set s.active = false "
            + "where s.offeringId = :offeringId and s.studentId = :studentId and s.active = true")
    int deactivateActive(UUID offeringId, UUID studentId);

    /**
     * T335：这场布置里还没跑完的提交数（PENDING / RUNNING，以及等派发的 QUEUED）。
     * 已交卷的 DONE / FAILED / SKIPPED 与已拒收都算跑完。
     */
    @Query(value = "select count(*) from submissions where offering_id = :offeringId "
            + "and rejected_at is null and grading_state in ('PENDING', 'RUNNING', 'QUEUED')",
            nativeQuery = true)
    int countOpenWork(UUID offeringId);

    /** T331：这批提交里仍然 active 的（重交后旧 attempt 会变 inactive，复核队列不该再列）。 */
    @Query("select s.id from Submission s where s.active = true and s.id in :ids")
    List<UUID> findActiveIds(java.util.Collection<UUID> ids);

    /** T092：派发时把这一场 QUEUED 的行批量放进队列；重复执行无副作用。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update submissions set grading_state = 'PENDING' "
            + "where offering_id = :offeringId and grading_state = 'QUEUED'", nativeQuery = true)
    int enqueueQueued(UUID offeringId);

    /**
     * T252：单份「让 AI 先批」——只把这一份从 QUEUED / FAILED 推进到 PENDING，不动同一场的其他提交。
     * 已派发、批改中、已完成、未交卷、已拒收都返回 0（调用方据此报冲突）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update submissions set grading_state = 'PENDING', grading_error = null, "
            + "grading_started_at = null "
            + "where id = :id and grading_state in ('QUEUED', 'FAILED') "
            + "and submitted_at is not null and rejected_at is null", nativeQuery = true)
    int enqueueOne(UUID id);

    /**
     * 已派发场次的迟到提交继续入队：宽限期过后才交的作业也进同一个 run（plan §7.3）。
     * 同样可以重复执行，没有 QUEUED 行时更新 0 行。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update submissions s set grading_state = 'PENDING' from offerings o "
            + "where s.offering_id = o.id and o.grading_dispatched_at is not null "
            + "and s.grading_state = 'QUEUED'", nativeQuery = true)
    int enqueueQueuedOfDispatched();

    /**
     * T091 僵尸清扫：只回收超过阈值的 RUNNING，正在跑的（started_at 较新）不动。
     * grading_started_at 置空，下一次领取会重新计时。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update submissions set grading_state = 'PENDING', grading_started_at = null, "
            + "grading_error = null "
            + "where grading_state = 'RUNNING' and grading_started_at is not null "
            + "and grading_started_at < :cutoff and grading_attempts < :maxAttempts", nativeQuery = true)
    int sweepStaleRunning(Instant cutoff, int maxAttempts);

    /**
     * T234：领了太多次还是跑不完的行，退成 FAILED 而不是无限回到队列——
     * FAILED 才会进「失败」计数，也才有 UI 上的「重试失败/卡住项」可点。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update submissions set grading_state = 'FAILED', grading_started_at = null, "
            + "grading_error = '批改多次未完成，已标记失败（可手动重试）' "
            + "where grading_state = 'RUNNING' and grading_started_at is not null "
            + "and grading_started_at < :cutoff and grading_attempts >= :maxAttempts", nativeQuery = true)
    int failStaleRunning(Instant cutoff, int maxAttempts);

    /**
     * T234：教师手动「重试失败/卡住项」时，把这一场超时的 RUNNING 也退回队列
     * （阈值比定时清扫短，见 {@code teachloop.grading.manual-stale-minutes}）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update submissions set grading_state = 'PENDING', grading_started_at = null, "
            + "grading_error = null "
            + "where offering_id = :offeringId and grading_state = 'RUNNING' "
            + "and grading_started_at is not null and grading_started_at < :cutoff", nativeQuery = true)
    int reclaimStaleRunning(UUID offeringId, Instant cutoff);

    /**
     * T094：一条聚合 SQL 返回 total/done/running/pending/failed，不在应用层做 N 次 count。
     * QUEUED 是「还没派发的待处理」，计进 pending，否则派发前进度会全是 0。
     */
    @Query(value = "select count(*) as total, "
            + "count(*) filter (where grading_state = 'DONE') as done, "
            + "count(*) filter (where grading_state = 'RUNNING') as running, "
            + "count(*) filter (where grading_state in ('PENDING', 'QUEUED')) as pending, "
            + "count(*) filter (where grading_state = 'FAILED') as failed "
            + "from submissions where offering_id = :offeringId", nativeQuery = true)
    GradingProgress progressOf(UUID offeringId);

    /** 投影接口：字段名与 SQL 别名一一对应。 */
    interface GradingProgress {
        long getTotal();

        long getDone();

        long getRunning();

        long getPending();

        long getFailed();
    }
}
