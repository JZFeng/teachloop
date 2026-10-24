package com.teachloop.grading.ai;

import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D-31：调用层退避重试。
 *
 * <p>只重试 {@link LlmCallException#retryable()} 为真的错误（429 / 5xx / 连接失败），
 * 最多 {@link RetryPolicy#maxAttempts()} 次；401 / 400 / 读超时第一次就放弃。
 * 与 {@code AiGradingEngine} 里「输出不合规」的纠正重试分开计数。
 *
 * <p>失败尝试只写 WARN 日志，不写 {@code ai_calls}（该表记录成功调用的 tokens 与耗时）。
 */
public class RetryingLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RetryingLlmClient.class);

    /** 测试里替换成记录器，避免真的睡 2 秒、8 秒。 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final LlmClient delegate;
    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final DoubleSupplier random;
    private final String label;

    public RetryingLlmClient(LlmClient delegate, RetryPolicy policy) {
        this(delegate, policy, Thread::sleep, Math::random, "ai");
    }

    RetryingLlmClient(LlmClient delegate, RetryPolicy policy, Sleeper sleeper, DoubleSupplier random, String label) {
        this.delegate = delegate;
        this.policy = policy;
        this.sleeper = sleeper;
        this.random = random;
        this.label = label == null || label.isBlank() ? "ai" : label;
    }

    /** 测试用：看被包住的真实客户端。 */
    public LlmClient delegate() {
        return delegate;
    }

    /** 重试次数为 0 时原样返回，不额外包一层。 */
    public static LlmClient wrap(LlmClient delegate, RetryPolicy policy) {
        if (delegate == null || policy == null || policy.maxAttempts() <= 0) {
            return delegate;
        }
        return new RetryingLlmClient(delegate, policy);
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        int attempt = 0;
        while (true) {
            try {
                return delegate.complete(request);
            } catch (LlmCallException ex) {
                attempt++;
                if (!ex.retryable() || attempt > policy.maxAttempts()) {
                    if (attempt > 1) {
                        log.warn("AI 调用放弃（{}，尝试 {} 次，{}）：{}",
                                label, attempt, ex.kind(), ex.getMessage());
                    }
                    throw ex;
                }
                long wait = policy.waitMillis(attempt, ex.retryAfterSeconds(), random);
                log.warn("AI 调用失败（{}，第 {} 次，{}），{} ms 后重试：{}",
                        label, attempt, ex.kind(), wait, ex.getMessage());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new LlmCallException("重试等待被中断", LlmCallException.Kind.OTHER, interrupted);
                }
            }
        }
    }
}
