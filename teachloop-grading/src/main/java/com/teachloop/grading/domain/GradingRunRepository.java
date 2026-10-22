package com.teachloop.grading.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GradingRunRepository extends JpaRepository<GradingRun, UUID> {

    Optional<GradingRun> findByOfferingId(UUID offeringId);

    /**
     * 幂等插入：同一 offering 并发派发时，唯一索引只让一条进入，其余走 {@code do nothing}。
     *
     * <p>用 native INSERT ... ON CONFLICT 而不是「先查后插 + 捕获约束异常」：后者会把当前
     * JPA 事务标记为 rollback-only，外层就再也不能继续写队列。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "insert into grading_runs (id, offering_id, engine, started_at, state) "
            + "values (:id, :offeringId, :engine, :startedAt, 'running') "
            + "on conflict (offering_id) where offering_id is not null do nothing", nativeQuery = true)
    int insertIfAbsent(UUID id, UUID offeringId, String engine, Instant startedAt);

    /**
     * T335：一场批改跑完就收尾；之后再来提交（重交 / 迟交 / 重试失败）会重新打开。
     * 条件更新保证重复执行无副作用。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update grading_runs set state = 'done', finished_at = :now "
            + "where offering_id = :offeringId and state = 'running'", nativeQuery = true)
    int finishIfRunning(UUID offeringId, Instant now);

    /** T335：有新工作进队时把已收尾的 run 重新打开（一场仍然只有一条 run）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update grading_runs set state = 'running', finished_at = null "
            + "where offering_id = :offeringId and state = 'done'", nativeQuery = true)
    int reopenIfDone(UUID offeringId);
}
