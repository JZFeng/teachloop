package com.teachloop.shared.error;

public enum ErrorCode {
    BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT,
    VALIDATION_FAILED, RATE_LIMITED, UNAVAILABLE, INTERNAL,
    /** 路由存在但不支持该方法（GET 打 POST 接口等），必须是 405 而不是兜底的 500。 */
    METHOD_NOT_ALLOWED,
    /** 账号已注册但未激活邮箱（009 AC-73.6），必须 403 且与「密码错」区分开。 */
    ACCOUNT_PENDING,
    /**
     * 011 / T561 / AC-93.4：教师资料未补全时的写操作拦截。必须 403，且 body 的 {@code code}
     * 保持为 {@code PROFILE_INCOMPLETE}——前端 `client.ts` 与 `App.tsx` 按这个字符串决定改道资料页。
     */
    PROFILE_INCOMPLETE,
    /**
     * 012 / AC-98.7：对已结课的班新建作业、或该生已没有未归档的参与班时提交，必须 409，
     * 且 body 的 {@code code} 是 {@code CLASS_ARCHIVED}——前端据此把入口置灰并说明原因，
     * 不是靠隐藏按钮（FR-220）。
     */
    CLASS_ARCHIVED
}
