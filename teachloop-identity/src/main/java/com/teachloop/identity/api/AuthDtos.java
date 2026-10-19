package com.teachloop.identity.api;

import com.teachloop.identity.domain.UserRole;

/** identity 对外暴露的只读视图。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record MeView(String id, String displayName, String email, UserRole role, String locale) {
    }

    public record UserView(String id, String displayName, String email, UserRole role) {
    }
}
