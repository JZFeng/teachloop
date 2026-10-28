package com.teachloop.shared.error;

public enum ErrorCode {
    BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT,
    VALIDATION_FAILED, RATE_LIMITED, UNAVAILABLE, INTERNAL,
    /** 路由存在但不支持该方法（GET 打 POST 接口等），必须是 405 而不是兜底的 500。 */
    METHOD_NOT_ALLOWED,
    /** 账号已注册但未激活邮箱（009 AC-73.6），必须 403 且与「密码错」区分开。 */
    ACCOUNT_PENDING
}
