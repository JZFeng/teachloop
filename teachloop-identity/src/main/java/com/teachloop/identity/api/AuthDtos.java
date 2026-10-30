package com.teachloop.identity.api;

import com.teachloop.identity.domain.UserRole;

/** identity 对外暴露的只读视图。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * 登录 / {@code GET /api/auth/me} 的当前用户视图。
     *
     * <p>{@code profileIncomplete}（T561 / US-93）：手机号或邮箱缺一个即为 true；管理员豁免。
     * 由 {@code ProfileCompletion.isIncomplete} 判定，与教师建班/发词的 403 判定同源。
     * 前端据此决定是否进 {@code /welcome}，不再依赖 403。
     */
    public record MeView(String id, String displayName, String email, UserRole role, String locale,
                         boolean profileIncomplete) {
    }

    public record UserView(String id, String displayName, String email, UserRole role) {
    }
}
