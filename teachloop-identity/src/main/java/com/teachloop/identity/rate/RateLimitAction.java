package com.teachloop.identity.rate;

/** 被限流的四类动作（T037）；字符串进数据库的 {@code rate_limits.action}，稳定不要改。 */
public enum RateLimitAction {

    LOGIN("login"),
    UPLOAD("upload"),
    GRADING_DISPATCH("grading_dispatch"),
    EVENTS("events");

    private final String action;

    RateLimitAction(String action) {
        this.action = action;
    }

    public String action() {
        return action;
    }
}
