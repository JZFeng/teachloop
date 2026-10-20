package com.teachloop.identity.rate;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 限流阈值与窗口（T037）。全部走 {@code @Value} 默认值，<b>不写进 application.yml</b>：
 * 允许部署时按环境覆盖，默认值直接写在代码里便于审计。
 *
 * <p>窗口是固定窗口（不是滑动窗口）：同一 key+action 在一个窗口内累计，窗口边界一起清零。
 * 这是有意的取舍——一条 SQL 的原子计数 + 可解释的 Retry-After，比滑动窗口少一个 Redis/内存结构。
 */
@Component
public class RateLimitSettings {

    /** @param limit 窗口内允许的请求数；<= 0 表示关闭该类限流 */
    public record Rule(int limit, long windowSeconds) {

        public Rule {
            if (windowSeconds <= 0) {
                throw new IllegalArgumentException("限流窗口必须大于 0 秒");
            }
        }

        public boolean enabled() {
            return limit > 0;
        }
    }

    private final Map<RateLimitAction, Rule> rules;

    public RateLimitSettings(
            @Value("${teachloop.rate-limit.login.limit:30}") int loginLimit,
            @Value("${teachloop.rate-limit.login.window-seconds:60}") long loginWindowSeconds,
            @Value("${teachloop.rate-limit.upload.limit:20}") int uploadLimit,
            @Value("${teachloop.rate-limit.upload.window-seconds:60}") long uploadWindowSeconds,
            @Value("${teachloop.rate-limit.grading-dispatch.limit:20}") int dispatchLimit,
            @Value("${teachloop.rate-limit.grading-dispatch.window-seconds:60}") long dispatchWindowSeconds,
            @Value("${teachloop.rate-limit.events.limit:120}") int eventsLimit,
            @Value("${teachloop.rate-limit.events.window-seconds:60}") long eventsWindowSeconds) {
        this.rules = new EnumMap<>(RateLimitAction.class);
        rules.put(RateLimitAction.LOGIN, new Rule(loginLimit, loginWindowSeconds));
        rules.put(RateLimitAction.UPLOAD, new Rule(uploadLimit, uploadWindowSeconds));
        rules.put(RateLimitAction.GRADING_DISPATCH, new Rule(dispatchLimit, dispatchWindowSeconds));
        rules.put(RateLimitAction.EVENTS, new Rule(eventsLimit, eventsWindowSeconds));
    }

    public Rule ruleFor(RateLimitAction action) {
        return rules.get(action);
    }
}
