package com.teachloop.shared.security;

import java.io.Serializable;
import java.util.UUID;

/**
 * 当前登录者，由 identity 模块的会话过滤器放入请求属性 {@code currentUser}。
 * 放在 shared，避免各业务模块依赖 identity 的内部类型。
 */
public record CurrentUser(UUID id, String role, String displayName) implements Serializable {

    public boolean is(String expected) {
        return expected.equals(role);
    }
}
