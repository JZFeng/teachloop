package com.teachloop.identity.ai;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** D-31：熔断状态的条件更新；半开试探权靠 {@link #electHalfOpen} 抢占。 */
public interface AiCircuitStateRepository extends JpaRepository<AiCircuitState, String> {

    /** 没有记录时补一行 CLOSED；并发插入用 ON CONFLICT 兜底。 */
    @Modifying
    @Query(value = "insert into ai_circuit_state(provider_key, state, consecutive_failures, updated_at) "
            + "values (:key, 'CLOSED', 0, :now) on conflict (provider_key) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("now") Instant now);

    /** OPEN 满冷却后，只有一个进程能把状态翻成 HALF_OPEN 并领走那 1 份试探。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiCircuitState s set s.state = 'HALF_OPEN', s.halfOpenStartedAt = :now, s.updatedAt = :now "
            + "where s.providerKey = :key and s.state = 'OPEN' and s.openedAt <= :cutoff")
    int electHalfOpen(@Param("key") String key, @Param("now") Instant now, @Param("cutoff") Instant cutoff);

    /** 试探进程崩溃：超过 probe-timeout 仍停在 HALF_OPEN 的，退回 OPEN 重新排队。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiCircuitState s set s.state = 'OPEN', s.openedAt = :now, s.halfOpenStartedAt = null, "
            + "s.updatedAt = :now where s.providerKey = :key and s.state = 'HALF_OPEN' "
            + "and s.halfOpenStartedAt <= :cutoff")
    int reopenStaleProbe(@Param("key") String key, @Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
