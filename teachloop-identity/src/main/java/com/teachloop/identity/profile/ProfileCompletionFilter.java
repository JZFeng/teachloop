package com.teachloop.identity.profile;

import com.teachloop.identity.domain.UserRepository;
import com.teachloop.shared.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * T561 / US-93：资料补全从「整站 403」改为「可跳过」。
 *
 * <p>改动前的行为（T034）：除补全页与登出外，普通请求一律 403 {@code PROFILE_INCOMPLETE}。
 * 改动后（AC-93.1）：**一律放行**，只把「资料是否完整」写进请求属性
 * {@link ProfileCompletion#ATTR}；教师写操作（建班 {@code ClassEndpoint.create}、
 * 发词 {@code VocabularyAssignmentEndpoint.create}）自己读属性返回 403（AC-93.4）。
 *
 * <p>开关 {@code teachloop.security.require-profile-completion} 自本次改动起不再影响放行结果：
 * 开、关两种取值下请求都放行、属性都照常写入，因此 {@code GET /api/auth/me} 的
 * {@code profileIncomplete} 在两种配置下都正确（T561 的「两种取值都要自洽」）。
 * 开关退役说明见 {@code SecurityConfig}。
 */
public class ProfileCompletionFilter extends OncePerRequestFilter {

    private final UserRepository users;

    public ProfileCompletionFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getMethod().equals("OPTIONS")) {
            Object principal = request.getAttribute("currentUser");
            if (principal instanceof CurrentUser me) {
                boolean incomplete = users.findById(me.id())
                        .map(ProfileCompletion::isIncomplete)
                        .orElse(false);
                request.setAttribute(ProfileCompletion.ATTR, incomplete);
            }
        }
        chain.doFilter(request, response);
    }
}
