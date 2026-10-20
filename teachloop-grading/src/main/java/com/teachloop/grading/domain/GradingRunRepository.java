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
}
