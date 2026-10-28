package com.teachloop.identity.rate;

/** 被限流的四类动作（T037）；字符串进数据库的 {@code rate_limits.action}，稳定不要改。 */
public enum RateLimitAction {

    LOGIN("login"),
    UPLOAD("upload"),
    GRADING_DISPATCH("grading_dispatch"),
    EVENTS("events"),
    /** 009：注册提交（按 IP）。 */
    REGISTER("register"),
    /** 009：重发激活邮件（按 IP）。 */
    RESEND_ACTIVATION("resend_activation"),
    /** 009：发验证码（登录 / 忘记密码共用，按 IP）。 */
    VERIFY_SEND("verify_send");

    private final String action;

    RateLimitAction(String action) {
        this.action = action;
    }

    public String action() {
        return action;
    }
}
