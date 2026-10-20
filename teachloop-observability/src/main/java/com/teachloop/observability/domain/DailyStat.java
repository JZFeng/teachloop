package com.teachloop.observability.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** T144：每日汇总的一行（day + metric → value），可重跑覆盖。 */
@Entity
@Table(name = "daily_stats")
@IdClass(DailyStatId.class)
public class DailyStat {

    @Id
    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Id
    @Column(nullable = false, length = 64)
    private String metric;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal value;

    protected DailyStat() {
    }

    public DailyStat(LocalDate day, String metric, BigDecimal value) {
        this.day = day;
        this.metric = metric;
        this.value = value;
    }

    public LocalDate getDay() {
        return day;
    }

    public String getMetric() {
        return metric;
    }

    public BigDecimal getValue() {
        return value;
    }
}
