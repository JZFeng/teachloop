package com.teachloop.sitting.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 峰谷时段口径（T093 / plan §7.4）：北京时间周一至周五 09:00–12:00、14:00–18:00 为高峰，
 * 其余时段（含周末全天）为空闲。
 *
 * <p>纯 java.time 实现、无状态、无依赖：<b>现在 → 可派发时刻</b>是一个可以直接单测的函数，
 * 不依赖服务器时区，也不依赖任何调度框架。
 *
 * <p>已知局限（plan §7.4 明写）：V1 不做中国法定节假日日历，节假日会被当成工作日，
 * 最多多等约 4 小时（14:00 之后最快要 18:00）。
 */
public final class GradingPeakHours {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final LocalTime MORNING_START = LocalTime.of(9, 0);
    private static final LocalTime MORNING_END = LocalTime.of(12, 0);
    private static final LocalTime AFTERNOON_START = LocalTime.of(14, 0);
    private static final LocalTime AFTERNOON_END = LocalTime.of(18, 0);

    /** 现在是否处于高峰时段。区间左闭右开：09:00 算高峰、12:00 已经不算。 */
    public boolean isPeak(Instant now) {
        ZonedDateTime local = now.atZone(ZONE);
        DayOfWeek day = local.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = local.toLocalTime();
        boolean morning = !time.isBefore(MORNING_START) && time.isBefore(MORNING_END);
        boolean afternoon = !time.isBefore(AFTERNOON_START) && time.isBefore(AFTERNOON_END);
        return morning || afternoon;
    }

    /**
     * 把「现在」换算成可派发时刻：空闲时段原样返回；高峰时段返回当天下一个空闲起点
     * （09:00–12:00 的下一站是 12:00，14:00–18:00 的下一站是 18:00）。
     */
    public Instant nextDispatchableAt(Instant now) {
        if (!isPeak(now)) {
            return now;
        }
        ZonedDateTime local = now.atZone(ZONE);
        LocalTime time = local.toLocalTime();
        LocalTime next = time.isBefore(MORNING_END) ? MORNING_END : AFTERNOON_END;
        return local.with(next).toInstant();
    }

    /** 现在就可以派发（等价于 nextDispatchableAt(now) == now）。 */
    public boolean isDispatchableNow(Instant now) {
        return nextDispatchableAt(now).equals(now);
    }
}
