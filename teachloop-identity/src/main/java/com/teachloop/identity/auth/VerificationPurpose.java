package com.teachloop.identity.auth;

/** 验证码/令牌的用途。频控与「能否复用」都按它分桶。 */
public enum VerificationPurpose {
    REGISTER_ACTIVATE,
    LOGIN,
    PASSWORD_RESET,
    CONTACT_CHANGE
}
