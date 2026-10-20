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
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, UserRepository users,
                                           RateLimitFilter rateLimitFilter, Environment environment) throws Exception {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        http
            // T033：生产开启双提交 Cookie CSRF；开发/测试关闭，避免所有测试与本地调用都要先取 token。
            .csrf(csrf -> {
                if (prod) {
                    csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse());
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
                        "/api/auth/login", "/api/auth/wechat", "/api/auth/teacher-invite").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/papers/**").hasAnyRole("TEACHER", "ADMIN")
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
