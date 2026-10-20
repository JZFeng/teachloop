package com.teachloop.grading.ai;

/** 模型调用失败（网络、鉴权、HTTP 非 2xx、响应体不可解析）。不自动重试，直接标 failed。 */
public class LlmCallException extends RuntimeException {

    public LlmCallException(String message) {
        super(message);
    }

    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
