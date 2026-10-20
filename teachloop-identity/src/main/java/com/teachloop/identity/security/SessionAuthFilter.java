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
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 会话里只放 uid，每次请求回查用户（改角色/禁用立即生效）。 */
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_UID = "teachloop.uid";

    private final UserRepository users;

    public SessionAuthFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        boolean authenticated = false;
        if (session != null) {
            Object uid = session.getAttribute(SESSION_UID);
            if (uid instanceof String s && !s.isBlank()) {
                var active = users.findById(UUID.fromString(s))
                        .filter(u -> u.getStatus() == UserStatus.ACTIVE);
                if (active.isPresent()) {
                    authenticate(request, active.get());
                    authenticated = true;
                }
            }
        }
        // 账号被停用/删除后，Spring Security 可能已从会话恢复旧认证；这里必须清掉，T036 才能立即踢下线。
        if (!authenticated) {
            SecurityContextHolder.clearContext();
            request.removeAttribute("currentUser");
        }
        chain.doFilter(request, response);
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
