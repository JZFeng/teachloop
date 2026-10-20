package com.teachloop.observability.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** daily_stats 的联合主键：一天一个指标一行。 */
public class DailyStatId implements Serializable {

    private LocalDate day;
    private String metric;

    protected DailyStatId() {
    }

    public DailyStatId(LocalDate day, String metric) {
        this.day = day;
        this.metric = metric;
    }

    public LocalDate getDay() {
        return day;
    }

    public String getMetric() {
        return metric;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DailyStatId that)) {
            return false;
        }
        return Objects.equals(day, that.day) && Objects.equals(metric, that.metric);
    }

    @Override
    public int hashCode() {
        return Objects.hash(day, metric);
    }
}
