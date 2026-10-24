package com.teachloop.grading.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * D-31 的调用层重试参数。
 *
 * <p>默认值：最多重试 2 次，退避 2 秒、8 秒，抖动 ±20%，429 的 Retry-After 上限 30 秒。
 * 退避时间可配；非法配置在装配时直接抛错（与「非法比例启动失败」一致，不静默降级）。
 */
public record RetryPolicy(int maxAttempts, List<Duration> backoff, double jitter, long retryAfterMaxSeconds) {

    public RetryPolicy {
        maxAttempts = Math.max(0, maxAttempts);
        backoff = backoff == null || backoff.isEmpty()
                ? List.of(Duration.ofSeconds(2), Duration.ofSeconds(8))
                : List.copyOf(backoff);
        jitter = Math.max(0d, Math.min(1d, jitter));
        retryAfterMaxSeconds = Math.max(0L, retryAfterMaxSeconds);
    }

    /** 从 `2000,8000` 这类逗号分隔的毫秒串构造；空串用默认退避。 */
    public static RetryPolicy of(int maxAttempts, String backoffCsv, double jitter, long retryAfterMaxSeconds) {
        List<Duration> waits = new ArrayList<>();
        if (backoffCsv != null && !backoffCsv.isBlank()) {
            for (String part : backoffCsv.split(",")) {
                String value = part.strip();
                if (value.isEmpty()) {
                    continue;
                }
                try {
                    long ms = Long.parseLong(value);
                    if (ms > 0) {
                        waits.add(Duration.ofMillis(ms));
                    }
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("teachloop.ai.retry.backoff-ms 里的值不是毫秒数: " + value, ex);
                }
            }
        }
        return new RetryPolicy(maxAttempts, waits, jitter, retryAfterMaxSeconds);
    }

    /**
     * 第 {@code retryNumber} 次重试（从 1 开始）要等待的毫秒数。
     *
     * <p>429 带 Retry-After 且不超过上限时优先用它；否则取配置的退避并加抖动。
     */
    public long waitMillis(int retryNumber, Long retryAfterSeconds, DoubleSupplier random) {
        if (retryAfterSeconds != null && retryAfterSeconds > 0 && retryAfterSeconds <= retryAfterMaxSeconds) {
            return retryAfterSeconds * 1000L;
        }
        long base = backoff.get(Math.min(Math.max(retryNumber - 1, 0), backoff.size() - 1)).toMillis();
        if (jitter <= 0) {
            return base;
        }
        double factor = 1.0 + ((random.getAsDouble() * 2.0) - 1.0) * jitter;
        return Math.max(1L, Math.round(base * factor));
    }
}
