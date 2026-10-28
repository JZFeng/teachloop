package com.teachloop.identity.security;

import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.profile.ProfileCompletionFilter;
import com.teachloop.identity.rate.RateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * 2026-10-25：只有 servlet 相关的两个 bean 限制在 Web 环境。
 * worker profile 是 non-web（application-worker.yml），没有 HttpSecurity/FilterRegistrationBean，
 * 类级禁用会连带把 passwordEncoder 一起拿掉，worker 起不来；所以条件加在方法上。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 限流过滤器在安全链里运行；这里禁用 Boot 对 Filter Bean 的自动注册，
     * 避免同一个过滤器在 servlet 层再跑一遍。
     */
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, UserRepository users,
                                           RateLimitFilter rateLimitFilter, Environment environment) throws Exception {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        http
            // T033：生产开启双提交 Cookie CSRF；开发/测试关闭，避免所有测试与本地调用都要先取 token。
            .csrf(csrf -> {
                if (prod) {
                    csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse());
                    // SPA 直接回传 Cookie 里的原始 token；XOR 掩码会让「Cookie 值」和
                    // handler 期待的「掩码值」不一致，导致所有 POST 401（2026-10-25 部署实测）。
                    csrf.csrfTokenRequestHandler(
                            new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler());
                } else {
                    csrf.disable();
                }
            })
            // 自定义过滤器每个请求都会设置 SecurityContext；默认的 session fixation 保护会因此
            // 每次请求都换一次 session id，导致「登录后第二个请求就 401」。登录时由我们自己显式建会话。
            .sessionManagement(s -> s
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(f -> f.none()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/healthz", "/actuator/health", "/actuator/health/**", "/api/csrf",
                        "/api/setup/**",
                        "/api/auth/login", "/api/auth/wechat", "/api/auth/teacher-invite",
                        // 009：注册、激活（GET/POST）、重发、通道发现都必须在未登录时可用
                        "/api/auth/register", "/api/auth/activate", "/api/auth/activate/resend",
                        "/api/auth/channels", "/api/auth/login/challenge", "/api/auth/login/verify",
                        "/api/auth/password-reset/challenge", "/api/auth/password-reset/verify").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // 2026-10-27：班级只属于师生。管理员既没有班级菜单，也不该能调班级接口
                // （此前 requireOwned 里的 ADMIN 例外允许管理员轮换任意班的邀请码）。
                .requestMatchers("/api/classes/**").hasAnyRole("TEACHER", "STUDENT")
                // 007-role-nav：教师专属功能。管理员只做 AI 配置，
                // 因此这里不再沿用 hasAnyRole(TEACHER, ADMIN)。
                .requestMatchers("/api/papers/**").hasRole("TEACHER")
                .requestMatchers("/api/reports/**").hasRole("TEACHER")
                .requestMatchers("/api/grading/**").hasRole("TEACHER")
                .requestMatchers("/api/submissions/*/review", "/api/submissions/*/review/**").hasRole("TEACHER")
                // 布置与批改相关的子路径：路径前缀与学生答题共用，必须细化到方法/子路径。
                .requestMatchers(HttpMethod.POST, "/api/offerings").hasRole("TEACHER")
                .requestMatchers(HttpMethod.POST,
                        "/api/offerings/*/publish",
                        "/api/offerings/*/scores/publish",
                        "/api/offerings/*/grading/**",
                        "/api/offerings/*/submissions/*/grading/**",
                        "/api/offerings/*/submissions/*/reject").hasRole("TEACHER")
                .requestMatchers(HttpMethod.GET,
                        "/api/offerings/*/submissions",
                        "/api/offerings/*/progress",
                        "/api/offerings/*/class-summary",
                        "/api/offerings/*/export/**").hasRole("TEACHER")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                    // 未认证 = 401；已认证但 CSRF 失败 = 403（T033 契约）。
                    .authenticationEntryPoint((req, res, ex) -> res.sendError(
                            Boolean.TRUE.equals(req.getAttribute("teachloop.authenticated"))
                                    ? HttpServletResponse.SC_FORBIDDEN
                                    : HttpServletResponse.SC_UNAUTHORIZED))
                    .accessDeniedHandler((req, res, ex) -> res.sendError(HttpServletResponse.SC_FORBIDDEN)))
            // 会话认证必须在 CSRF 之前：否则已登录用户的写请求缺 token 会被当成匿名 401。
            .addFilterBefore(new SessionAuthFilter(users), CsrfFilter.class)
            // 顺序：会话认证（设置 currentUser）→ 限流 → 授权判断。
            // 限流必须在授权之前，登录接口本身 permitAll，所以登录也在限流覆盖范围内。
            .addFilterAfter(rateLimitFilter, SessionAuthFilter.class)
            .addFilterAfter(new ProfileCompletionFilter(users,
                    environment.getProperty("teachloop.security.require-profile-completion", Boolean.class, false)),
                    RateLimitFilter.class)
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable());
        return http.build();
    }
}
