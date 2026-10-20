package com.teachloop.identity.rate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 固定窗口限流计数（T037）：一条原子 upsert 完成「计数并取回本窗口累计值」。
 *
 * <pre>
 * insert into rate_limits (id, key, action, window_start, count) values (?, ?, ?, ?, 1)
 * on conflict (key, action, window_start) do update set count = count + 1
 * returning count
 * </pre>
 *
 * <p>并发安全由唯一键 + 行锁保证：同一窗口的并发请求在唯一键上串行，
 * 每个请求都能拿到自己这次累加后的精确值，不需要应用层锁。
 */
@Service
public class RateLimiter {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RateLimiter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @param retryAfterSeconds 距离窗口结束还有多少秒（至少 1） */
    public record Decision(boolean allowed, int limit, long retryAfterSeconds) {
    }

    public Decision check(RateLimitAction action, String key, RateLimitSettings.Rule rule) {
        Instant now = Instant.now(clock);
        long windowSeconds = rule.windowSeconds();
        long windowStartEpoch = Math.floorDiv(now.getEpochSecond(), windowSeconds) * windowSeconds;
        Instant windowStart = Instant.ofEpochSecond(windowStartEpoch);
        long retryAfter = Math.max(1,
                windowStart.plusSeconds(windowSeconds).getEpochSecond() - now.getEpochSecond());

        Integer count = jdbc.queryForObject("insert into rate_limits (id, key, action, window_start, count) "
                        + "values (?, ?, ?, ?, 1) "
                        + "on conflict (key, action, window_start) do update set count = rate_limits.count + 1 "
                        + "returning count",
                Integer.class, UUID.randomUUID(), key, action.action(), Timestamp.from(windowStart));
        int current = count == null ? 1 : count;
        return new Decision(current <= rule.limit(), rule.limit(), retryAfter);
    }
}
