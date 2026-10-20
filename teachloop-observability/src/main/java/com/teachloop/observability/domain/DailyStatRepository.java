package com.teachloop.observability.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DailyStatRepository extends JpaRepository<DailyStat, DailyStatId> {

    List<DailyStat> findByDay(LocalDate day);

    /**
     * 幂等写入：同一天重跑时覆盖 value，不产生第二行（主键 (day, metric) 保证）。
     *
     * <p>用原生 upsert 而不是先 delete 再 insert：重跑中途失败也不会留下半天的空数据。
     */
    @Modifying
    @Transactional
    @Query(value = """
            insert into daily_stats (day, metric, value)
            values (:day, :metric, :value)
            on conflict (day, metric) do update set value = excluded.value
            """, nativeQuery = true)
    int upsert(@Param("day") LocalDate day, @Param("metric") String metric,
               @Param("value") BigDecimal value);
}
