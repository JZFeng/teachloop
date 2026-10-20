package com.teachloop.sitting.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OfferingRepository extends JpaRepository<Offering, UUID> {

    List<Offering> findByStatusOrderByDueAtDesc(OfferingStatus status);

    List<Offering> findByPaperId(UUID paperId);

    /**
     * T092 到期扫描：已发布、未派发、且过了「截止 + 宽限期」的场次。
     * 宽限期是小时（grace_hours），用 make_interval 让边界判断留在数据库，避免应用层时区误差。
     */
    @Query(value = "select o.id from offerings o "
            + "where o.status = 'PUBLISHED' and o.grading_dispatched_at is null "
            + "and o.due_at + make_interval(hours => o.grace_hours) <= :now", nativeQuery = true)
    List<UUID> findDueForGrading(Instant now);

    /**
     * 派发的互斥：条件更新成功（返回 1）的那一次才去建 run。
     *
     * <p><b>数据库条件更新就是互斥</b>：两个实例/线程同时派发同一场时，第二条 UPDATE 会等第一条
     * 提交，然后重新计算 <code>grading_dispatched_at IS NULL</code> 得到 0 行，于是跳过派发。
     * 因此不需要 ShedLock、行锁或分布式锁。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update offerings set grading_dispatched_at = :now "
            + "where id = :id and grading_dispatched_at is null", nativeQuery = true)
    int markGradingDispatched(UUID id, Instant now);
}
