package com.teachloop.shared.time;

import java.time.ZoneId;
import java.util.Optional;

/**
 * 时区解析（011 / US-99）：请求头 &gt; 用户资料 &gt; 产品默认。
 *
 * <p>纯函数、无 Spring 依赖，便于单测覆盖夏令时与非法值。非法值一律回落，**不抛异常**——
 * 时区错了只影响「今天」的计数口径，不该让整个请求失败。
 */
public final class ZoneResolution {

    private ZoneResolution() {
    }

    /**
     * @param header          {@code X-Timezone}（IANA 名），可为空
     * @param stored          {@code users.timezone}，可为空
     * @param productDefault  产品默认时区，不可为空
     * @return 永远返回一个可用时区
     */
    public static ZoneId resolve(String header, Optional<String> stored, ZoneId productDefault) {
        ZoneId fromHeader = parse(header);
        if (fromHeader != null) {
            return fromHeader;
        }
        if (stored != null) {
            ZoneId fromStored = parse(stored.orElse(null));
            if (fromStored != null) {
                return fromStored;
            }
        }
        return productDefault;
    }

    /**
     * 只接受 IANA 时区名；空串、超长、未知名称一律返回 null。
     *
     * <p>011 / T557 起改为 public：调用方需要在「头非法」时记一条 warning（AC-99.2），
     * 解析结果本身仍由 {@link #resolve} 统一决定，不给调用方另写一套回落链。
     */
    public static ZoneId parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 64) {
            return null;
        }
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
