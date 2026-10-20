package com.teachloop.identity.security;

import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.rate.RateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
                                           RateLimitFilter rateLimitFilter) throws Exception {
        http
            // 无状态 Bearer 不需要 CSRF；本项目用会话 + Cookie，CSRF 防护在后续阶段接入
            .csrf(csrf -> csrf.disable())
            // 自定义过滤器每个请求都会设置 SecurityContext；默认的 session fixation 保护会因此
            // 每次请求都换一次 session id，导致「登录后第二个请求就 401」。登录时由我们自己显式建会话。
            .sessionManagement(s -> s
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(f -> f.none()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/healthz", "/actuator/health", "/actuator/health/**",
                        "/api/auth/login", "/api/auth/wechat", "/api/auth/teacher-invite").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(new SessionAuthFilter(users), UsernamePasswordAuthenticationFilter.class)
            // 顺序：会话认证（设置 currentUser）→ 限流 → 授权判断。
            // 限流必须在授权之前，登录接口本身 permitAll，所以登录也在限流覆盖范围内。
            .addFilterAfter(rateLimitFilter, SessionAuthFilter.class)
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable());
        return http.build();
    }
}
