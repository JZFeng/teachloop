package com.teachloop.grading.ai;

/** 模型输出不符合输出契约（T082）。由 {@code AiGradingEngine} 重试一次，仍失败则向上抛。 */
public class InvalidGradeOutputException extends RuntimeException {

    public InvalidGradeOutputException(String message) {
        super(message);
    }

    public InvalidGradeOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
