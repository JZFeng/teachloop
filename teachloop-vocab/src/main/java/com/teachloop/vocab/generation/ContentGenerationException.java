package com.teachloop.vocab.generation;

/** 内容生成失败（AI 未配置、超时、输出不合法）。由 worker 转成退避重试或 FAILED。 */
public class ContentGenerationException extends RuntimeException {

    public ContentGenerationException(String message) {
        super(message);
    }

    public ContentGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
