package com.teachloop.identity.security;

import com.teachloop.identity.domain.User;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 会话里只放 uid，每次请求回查用户（改角色/禁用立即生效）。 */
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_UID = "teachloop.uid";

    /**
     * 2026-10-27：会话建立时间。改密码会把 {@code users.session_invalid_before} 置为 now()，
     * 早于它登录的会话一律作废——这就是「改完密码踢掉其他设备」的实现。
     */
    public static final String SESSION_LOGIN_AT = "teachloop.loginAt";

    private final UserRepository users;

    public SessionAuthFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        boolean authenticated = false;
        boolean hadUid = false;
        boolean invalidated = false;
        if (session != null) {
            Object uid = session.getAttribute(SESSION_UID);
            if (uid instanceof String s && !s.isBlank()) {
                hadUid = true;
                var active = users.findById(UUID.fromString(s))
                        .filter(u -> u.getStatus() == UserStatus.ACTIVE);
                if (active.isPresent()) {
                    User user = active.get();
                    if (staleAfterPasswordChange(session, user)) {
                        // 改密码之后建立的会话才有效；旧会话直接判未登录。
                        session.invalidate();
                        invalidated = true;
                    } else {
                        authenticate(request, user);
                        authenticated = true;
                    }
                }
            }
        }
        // 账号停用/删除/改过密码：立刻销毁会话，后续请求按未登录 401，而不是拿着旧会话 403。
        if (hadUid && !authenticated && session != null && !invalidated) {
            session.invalidate();
        }
        // 账号被停用/删除后，Spring Security 可能已从会话恢复旧认证；这里必须清掉，T036 才能立即踢下线。
        if (!authenticated) {
            SecurityContextHolder.clearContext();
            request.removeAttribute("currentUser");
        }
        request.setAttribute("teachloop.authenticated", authenticated);
        chain.doFilter(request, response);
    }

    /** 会话建立时间早于用户最近一次改密码 → 作废。历史会话没有 loginAt，按「比任何时间都早」处理。 */
    private static boolean staleAfterPasswordChange(HttpSession session, User user) {
        var invalidBefore = user.getSessionInvalidBefore();
        if (invalidBefore == null) {
            return false;
        }
        Object loginAt = session.getAttribute(SESSION_LOGIN_AT);
        if (!(loginAt instanceof Instant at)) {
            return true;
        }
        return at.isBefore(invalidBefore);
    }

    private void authenticate(HttpServletRequest request, User u) {
        var auth = new UsernamePasswordAuthenticationToken(
                new CurrentUser(u.getId(), u.getRole().name(), u.getDisplayName()),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
        request.setAttribute("currentUser", auth.getPrincipal());
    }
}
