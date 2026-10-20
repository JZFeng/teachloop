package com.teachloop.identity.profile;

import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserStatus;
import com.teachloop.identity.security.SessionAuthFilter;
import com.teachloop.shared.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * T034：首次登录后必须补全手机号与邮箱；未补全时除补全页与登出外一律 403。
 * 管理员豁免：管理员由 CLI 创建，避免把自己锁在门外。
 */
public class ProfileCompletionFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED = Set.of(
            "/api/auth/me", "/api/auth/logout", "/api/profile", "/healthz", "/actuator/health");

    private final UserRepository users;
    private final boolean enabled;

    public ProfileCompletionFilter(UserRepository users, boolean enabled) {
        this.users = users;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled || request.getMethod().equals("OPTIONS") || allowed(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        Object principal = request.getAttribute("currentUser");
        if (principal instanceof CurrentUser me && !me.is("ADMIN")) {
            boolean complete = users.findById(me.id())
                    .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                    .map(u -> u.getPhone() != null && !u.getPhone().isBlank()
                            && u.getEmail() != null && !u.getEmail().isBlank())
                    .orElse(false);
            if (!complete) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"PROFILE_INCOMPLETE\","
                        + "\"message\":\"请先补全手机号与邮箱\",\"detail\":\"/profile\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean allowed(String uri) {
        return ALLOWED.stream().anyMatch(uri::startsWith);
    }
}
