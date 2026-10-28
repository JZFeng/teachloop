package com.teachloop.identity.domain;

public enum UserStatus {
    ACTIVE,
    /** 已注册但邮箱尚未激活：不能登录、不能访问任何需要认证的接口，可重发激活邮件。 */
    PENDING,
    DISABLED
}
