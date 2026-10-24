package com.teachloop.grading.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D-31：调用层重试只覆盖 429 / 5xx / 连接失败，退避与 Retry-After 按契约。 */
class RetryingLlmClientTest {

    private static final LlmClient.LlmRequest REQUEST = new LlmClient.LlmRequest("system", "user", false);

    private static LlmClient.LlmResponse ok() {
        return new LlmClient.LlmResponse("deepseek-flash", "ok", 1, 1, 5);
    }

    @Test
    @DisplayName("429 与 5xx 各重试一次，按 2s / 8s 退避后成功")
    void retriesRetryableFailuresThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        LlmClient delegate = request -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                throw new LlmCallException("429", LlmCallException.Kind.RATE_LIMIT);
            }
            if (n == 2) {
                throw new LlmCallException("503", LlmCallException.Kind.SERVER);
            }
            return ok();
        };
        RetryingLlmClient client = new RetryingLlmClient(delegate, RetryPolicy.of(2, "2000,8000", 0d, 30),
                sleeps::add, () -> 0.5, "test");

        assertThat(client.complete(REQUEST).content()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
        assertThat(sleeps).containsExactly(2000L, 8000L);
    }

    @Test
    @DisplayName("重试次数用尽后抛出最后一次失败，共 3 次调用")
    void throwsAfterRetriesExhausted() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        LlmClient delegate = request -> {
            calls.incrementAndGet();
            throw new LlmCallException("503", LlmCallException.Kind.SERVER);
        };
        RetryingLlmClient client = new RetryingLlmClient(delegate, RetryPolicy.of(2, "2000,8000", 0d, 30),
                sleeps::add, () -> 0.5, "test");

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(LlmCallException.class)
                .satisfies(ex -> assertThat(((LlmCallException) ex).kind())
                        .isEqualTo(LlmCallException.Kind.SERVER));
        assertThat(calls.get()).isEqualTo(3);
        assertThat(sleeps).containsExactly(2000L, 8000L);
    }

    @Test
    @DisplayName("401 / 400 / 读超时不重试：只调用一次")
    void doesNotRetryNonRetryableKinds() {
        for (LlmCallException.Kind kind : List.of(LlmCallException.Kind.AUTH,
                LlmCallException.Kind.BAD_REQUEST, LlmCallException.Kind.TIMEOUT,
                LlmCallException.Kind.EMPTY, LlmCallException.Kind.OTHER)) {
            AtomicInteger calls = new AtomicInteger();
            List<Long> sleeps = new ArrayList<>();
            LlmClient delegate = request -> {
                calls.incrementAndGet();
                throw new LlmCallException("no retry", kind);
            };
            RetryingLlmClient client = new RetryingLlmClient(delegate, RetryPolicy.of(2, "2000,8000", 0d, 30),
                    sleeps::add, () -> 0.5, "test");

            assertThatThrownBy(() -> client.complete(REQUEST)).isInstanceOf(LlmCallException.class);
            assertThat(calls.get()).as("kind=%s", kind).isEqualTo(1);
            assertThat(sleeps).as("kind=%s", kind).isEmpty();
        }
    }

    @Test
    @DisplayName("429 的 Retry-After 不超过上限时优先使用")
    void honorsRetryAfterWithinLimit() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        LlmClient delegate = request -> {
            if (calls.incrementAndGet() == 1) {
                throw new LlmCallException("429", LlmCallException.Kind.RATE_LIMIT, null, 15L);
            }
            return ok();
        };
        RetryingLlmClient client = new RetryingLlmClient(delegate, RetryPolicy.of(2, "2000,8000", 0d, 30),
                sleeps::add, () -> 0.5, "test");

        client.complete(REQUEST);
        assertThat(sleeps).containsExactly(15000L);
    }

    @Test
    @DisplayName("Retry-After 超过上限时退回配置的退避")
    void ignoresRetryAfterAboveLimit() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        LlmClient delegate = request -> {
            if (calls.incrementAndGet() == 1) {
                throw new LlmCallException("429", LlmCallException.Kind.RATE_LIMIT, null, 120L);
            }
            return ok();
        };
        RetryingLlmClient client = new RetryingLlmClient(delegate, RetryPolicy.of(2, "2000,8000", 0d, 30),
                sleeps::add, () -> 0.5, "test");

        client.complete(REQUEST);
        assertThat(sleeps).containsExactly(2000L);
    }

    @Test
    @DisplayName("抖动在配置范围内调整退避")
    void appliesJitter() {
        RetryPolicy policy = RetryPolicy.of(2, "2000,8000", 0.5, 30);
        assertThat(policy.waitMillis(1, null, () -> 0.0)).isEqualTo(1000L);
        assertThat(policy.waitMillis(1, null, () -> 1.0)).isEqualTo(3000L);
        assertThat(policy.waitMillis(2, null, () -> 0.5)).isEqualTo(8000L);
    }

    @Test
    @DisplayName("重试次数为 0 时原样返回，不额外包一层")
    void wrapReturnsDelegateWhenDisabled() {
        LlmClient delegate = request -> ok();
        assertThat(RetryingLlmClient.wrap(delegate, RetryPolicy.of(0, "2000,8000", 0d, 30)))
                .isSameAs(delegate);
    }

    @Test
    @DisplayName("HTTP 状态映射：429 / 5xx / 401 / 4xx")
    void classifiesHttpStatus() {
        assertThat(DeepSeekLlmClient.classifyStatus(429)).isEqualTo(LlmCallException.Kind.RATE_LIMIT);
        assertThat(DeepSeekLlmClient.classifyStatus(500)).isEqualTo(LlmCallException.Kind.SERVER);
        assertThat(DeepSeekLlmClient.classifyStatus(503)).isEqualTo(LlmCallException.Kind.SERVER);
        assertThat(DeepSeekLlmClient.classifyStatus(401)).isEqualTo(LlmCallException.Kind.AUTH);
        assertThat(DeepSeekLlmClient.classifyStatus(403)).isEqualTo(LlmCallException.Kind.AUTH);
        assertThat(DeepSeekLlmClient.classifyStatus(400)).isEqualTo(LlmCallException.Kind.BAD_REQUEST);
        assertThat(DeepSeekLlmClient.classifyStatus(404)).isEqualTo(LlmCallException.Kind.BAD_REQUEST);
    }

    @Test
    @DisplayName("IO 异常映射：连接超时/拒绝连接可重试，读超时不可重试")
    void classifiesIoFailures() {
        assertThat(DeepSeekLlmClient.classifyIo(new HttpConnectTimeoutException("connect timeout")))
                .isEqualTo(LlmCallException.Kind.CONNECT);
        assertThat(DeepSeekLlmClient.classifyIo(new ConnectException("refused")))
                .isEqualTo(LlmCallException.Kind.CONNECT);
        assertThat(DeepSeekLlmClient.classifyIo(new java.net.http.HttpTimeoutException("read timeout")))
                .isEqualTo(LlmCallException.Kind.TIMEOUT);
        assertThat(DeepSeekLlmClient.classifyIo(new IOException("other")))
                .isEqualTo(LlmCallException.Kind.OTHER);
    }

    @Test
    @DisplayName("退避串非法时启动即失败，不静默降级")
    void rejectsInvalidBackoff() {
        assertThatThrownBy(() -> RetryPolicy.of(2, "2s,8s", 0d, 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("默认退避为 2 秒、8 秒")
    void usesDefaultBackoff() {
        RetryPolicy policy = RetryPolicy.of(2, "", 0d, 30);
        assertThat(policy.backoff()).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(8));
    }
}
