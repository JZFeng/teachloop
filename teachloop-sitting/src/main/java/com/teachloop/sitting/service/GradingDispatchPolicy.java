package com.teachloop.sitting.service;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 自动派发是否受峰谷限制（T093）。手动派发不经过这里，任何时刻都能执行。
 *
 * <p>开关 <code>teachloop.grading.peak-aware</code>（默认 true）。关闭后自动派发也立即执行，
 * 因为「不能因为省钱让老师等反馈」。
 */
@Component
public class GradingDispatchPolicy {

    private final boolean peakAware;
    private final GradingPeakHours peakHours = new GradingPeakHours();

    public GradingDispatchPolicy(@Value("${teachloop.grading.peak-aware:true}") boolean peakAware) {
        this.peakAware = peakAware;
    }

    public boolean isPeakAware() {
        return peakAware;
    }

    /** 自动派发此刻是否可以执行。 */
    public boolean mayDispatchNow(Instant now) {
        return !peakAware || peakHours.isDispatchableNow(now);
    }

    /** 自动派发最早可以执行的时刻：空闲时段就是现在，高峰时段是下一个空闲起点。 */
    public Instant nextDispatchableAt(Instant now) {
        return peakAware ? peakHours.nextDispatchableAt(now) : now;
    }
}
