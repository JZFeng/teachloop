package com.teachloop.shared.error;

/** 统一错误响应。detail 可为 null；绝不含堆栈与内部路径。 */
public record ApiError(String code, String message, String detail) {
    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(code.name(), message, null);
    }
}
