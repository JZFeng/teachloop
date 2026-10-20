package com.teachloop.observability.log;

import com.teachloop.shared.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * T140 / AC-16.1：每请求一行结构化日志。
 *
 * <p>字段只有 requestId、userId、method、route、status、durationMs、ts；
 * **不记录请求/响应正文、查询参数或请求头**，避免把作业内容与密码写进日志。
 *
 * <p>顺序 {@code -90}：排在 Spring Security 过滤器链（-100）之后，才能拿到会话解析出的
 * {@code currentUser}；同时日志写入失败绝不影响请求本身。
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final int ORDER = -90;

    private final LogSink sink;
    private final Clock clock;

    public RequestLoggingFilter(LogSink sink, Clock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Instant startedAt = Instant.now(clock);
        long started = System.nanoTime();
        String requestId = requestId(request);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            record(request, requestId, status, durationMs, startedAt);
        }
    }

    private void record(HttpServletRequest request, String requestId, int status, long durationMs,
                        Instant startedAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ts", startedAt.toString());
        fields.put("requestId", requestId);
        fields.put("userId", userId(request));
        fields.put("method", request.getMethod());
        fields.put("route", route(request));
        fields.put("status", status);
        fields.put("durationMs", durationMs);
        try {
            sink.write(LogKind.REQUESTS, startedAt, fields);
        } catch (RuntimeException ex) {
            // 日志不是业务：写失败只告警，绝不改变请求结果。
            log.warn("请求日志写入失败: {}", ex.getMessage());
        }
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader(REQUEST_ID_HEADER);
        if (header != null && !header.isBlank()) {
            return header.strip();
        }
        return UUID.randomUUID().toString();
    }

    /** 只记会话用户 id，不记姓名或联系方式。 */
    private static String userId(HttpServletRequest request) {
        Object principal = request.getAttribute("currentUser");
        return principal instanceof CurrentUser user ? user.id().toString() : null;
    }

    /** 路由模板（如 /api/offerings/{offeringId}/review）优先，匹配不到才退回原始 URI。 */
    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }
}
