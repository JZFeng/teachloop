package com.teachloop.identity.rate;

import com.teachloop.shared.error.ApiError;
import com.teachloop.shared.error.ErrorCode;
import com.teachloop.shared.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 四类端点限流（T037）：登录 / 文件上传 / 手动派发批改 / 事件上报。
 *
 * <p>过滤器挂在 Spring Security 链里、{@code SessionAuthFilter} 之后、授权判断之前，
 * 因此：
 * <ul>
 *   <li>登录接口本身是 permitAll，限流在登录逻辑之前生效（第 N+1 次直接 429，不再验密码）；</li>
 *   <li>其余三类动作能拿到会话用户，按用户计数（未登录请求交给授权层 401，不占配额）。</li>
 * </ul>
 *
 * <p>超限响应统一为 429 + {@code ApiError(code=RATE_LIMITED)} + {@code Retry-After} 秒数。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String USER_KEY_PREFIX = "user:";
    private static final String IP_KEY_PREFIX = "ip:";

    private final RateLimitSettings settings;
    private final RateLimiter limiter;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimitSettings settings, RateLimiter limiter, ObjectMapper mapper) {
        this.settings = settings;
        this.limiter = limiter;
        this.mapper = mapper;
    }

    private record Target(RateLimitAction action, String key) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Target target = resolve(request);
        if (target == null) {
            chain.doFilter(request, response);
            return;
        }
        RateLimitSettings.Rule rule = settings.ruleFor(target.action());
        if (!rule.enabled()) {
            chain.doFilter(request, response);
            return;
        }
        RateLimiter.Decision decision = limiter.check(target.action(), target.key(), rule);
        if (decision.allowed()) {
            response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
            chain.doFilter(request, response);
            return;
        }
        reject(response, decision);
    }

    /** 命中四类动作时返回动作与计数 key；其余请求直接放行。 */
    private Target resolve(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = path(request);
        boolean post = "POST".equalsIgnoreCase(request.getMethod());

        // 登录：按 IP（登录前没有用户身份）。不读请求体，避免破坏后续 JSON 解析。
        if (post && "/api/auth/login".equals(path)) {
            return new Target(RateLimitAction.LOGIN, IP_KEY_PREFIX + request.getRemoteAddr());
        }
        if (!post) {
            return null;
        }
        if (path.matches("^/api/offerings/[^/]+/submissions/file$")) {
            return userTarget(request, RateLimitAction.UPLOAD);
        }
        if (path.matches("^/api/offerings/[^/]+/grading/dispatch$")) {
            return userTarget(request, RateLimitAction.GRADING_DISPATCH);
        }
        if ("/api/events".equals(path) || path.startsWith("/api/events/")) {
            return userTarget(request, RateLimitAction.EVENTS);
        }
        return null;
    }

    private Target userTarget(HttpServletRequest request, RateLimitAction action) {
        Object principal = request.getAttribute("currentUser");
        if (!(principal instanceof CurrentUser user)) {
            // 未登录：授权层会返回 401，这里不占用户配额
            return null;
        }
        return new Target(action, USER_KEY_PREFIX + user.id());
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    private void reject(HttpServletResponse response, RateLimiter.Decision decision) throws IOException {
        ApiError error = new ApiError(ErrorCode.RATE_LIMITED.name(), "请求过于频繁，请稍后再试",
                "retry-after " + decision.retryAfterSeconds() + "s");
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(error));
    }
}
