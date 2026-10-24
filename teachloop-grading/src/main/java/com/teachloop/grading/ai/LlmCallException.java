package com.teachloop.grading.ai;

/**
 * 模型调用失败（网络、鉴权、HTTP 非 2xx、响应体不可解析）。
 *
 * <p>D-31：{@link Kind} 是重试与熔断的唯一判据，不再靠 message 字符串匹配。
 * 可重试的只有 {@code RATE_LIMIT} / {@code SERVER} / {@code CONNECT}；
 * 鉴权、参数错误与读超时重发也不会成功，由 {@link #retryable()} 排除。
 */
public class LlmCallException extends RuntimeException {

    public enum Kind {
        /** HTTP 429，限流。 */
        RATE_LIMIT,
        /** HTTP 5xx，对端服务错误。 */
        SERVER,
        /** 连接失败：拒绝连接、域名解析失败、连接超时。 */
        CONNECT,
        /** 401 / 403。 */
        AUTH,
        /** 400 / 404 等参数或资源错误。 */
        BAD_REQUEST,
        /** 读超时（单次调用跑满 timeout），重发会再次占满一轮。 */
        TIMEOUT,
        /** 响应为空或不可解析。 */
        EMPTY,
        /** 其它。 */
        OTHER
    }

    private final Kind kind;
    private final Long retryAfterSeconds;

    public LlmCallException(String message) {
        this(message, Kind.OTHER, null, null);
    }

    public LlmCallException(String message, Throwable cause) {
        this(message, Kind.OTHER, cause, null);
    }

    public LlmCallException(String message, Kind kind) {
        this(message, kind, null, null);
    }

    public LlmCallException(String message, Kind kind, Throwable cause) {
        this(message, kind, cause, null);
    }

    public LlmCallException(String message, Kind kind, Throwable cause, Long retryAfterSeconds) {
        super(message, cause);
        this.kind = kind == null ? Kind.OTHER : kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Kind kind() {
        return kind;
    }

    /** 429 响应里的 Retry-After（秒）；没有时为 null。 */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** 只有限流、5xx、连接失败值得重发。 */
    public boolean retryable() {
        return kind == Kind.RATE_LIMIT || kind == Kind.SERVER || kind == Kind.CONNECT;
    }
}
