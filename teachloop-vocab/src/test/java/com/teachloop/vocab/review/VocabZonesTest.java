package com.teachloop.vocab.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.shared.port.Ports;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 011 / T557：学生可见「今天」的解析与日界。
 *
 * <p>覆盖 AC-99.1（优先级）、AC-99.2（非法值回落、不抛）与 AC-99.4（洛杉矶两个夏令时切换日）。
 * 不启 Spring：{@link Clock} 固定成某个 UTC 时刻，日界只由纯计算决定。
 */
class VocabZonesTest {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** ObjectProvider 的最小替身：只有 getIfAvailable 被用到。 */
    private static ObjectProvider<Ports.UserZonePort> zonePort(String stored) {
        Ports.UserZonePort port = userId -> Optional.ofNullable(stored).map(Ports.UserZone::new);
        return new ObjectProvider<>() {
            @Override
            public Ports.UserZonePort getObject(Object... args) {
                return port;
            }

            @Override
            public Ports.UserZonePort getIfAvailable() {
                return port;
            }

            @Override
            public Ports.UserZonePort getIfUnique() {
                return port;
            }
        };
    }

    private static VocabZones zonesAt(String instant, String stored) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new VocabZones(zonePort(stored), clock, "Asia/Shanghai");
    }

    private static VocabZones zonesAt(String instant) {
        return zonesAt(instant, null);
    }

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000011");

    // ────────────────────────────────────────────── AC-99.1 / AC-99.2：解析

    @Test
    @DisplayName("AC-99.1：头 > 资料 > 产品默认")
    void priorityHeaderThenStoredThenProduct() {
        VocabZones zones = zonesAt("2026-10-31T16:01:00Z", "Europe/London");
        assertThat(zones.resolve("America/Los_Angeles", USER)).isEqualTo(LA);
        assertThat(zones.resolve(null, USER)).isEqualTo(ZoneId.of("Europe/London"));
        assertThat(zones.resolve("Mars/Base", USER)).isEqualTo(ZoneId.of("Europe/London"));
    }

    @Test
    @DisplayName("AC-99.2：两端都非法时回落产品默认，且不抛异常")
    void illegalValuesFallBackToProduct() {
        VocabZones zones = zonesAt("2026-10-31T16:01:00Z", "Not/AZone");
        assertThat(zones.resolve("Mars/Base", USER)).isEqualTo(SHANGHAI);
        assertThat(zones.resolve("", USER)).isEqualTo(SHANGHAI);
        assertThat(zones.resolve("America/" + "x".repeat(80), USER)).isEqualTo(SHANGHAI);
    }

    // ────────────────────────────────────────────── AC-99.3：同一时刻两个时区

    @Test
    @DisplayName("AC-99.3：北京时间 11-01 00:01 与洛杉矶 10-31 09:01 是同一时刻，日界不同")
    void sameInstantDifferentDayStart() {
        VocabZones zones = zonesAt("2026-10-31T16:01:00Z");
        // 北京：2026-11-01 00:01 → 当天 0 点 = 2026-10-31T16:00Z
        assertThat(zones.dayStart(SHANGHAI)).isEqualTo(Instant.parse("2026-10-31T16:00:00Z"));
        // 洛杉矶：2026-10-31 09:01（PDT）→ 当天 0 点 = 2026-10-31T07:00Z
        assertThat(zones.dayStart(LA)).isEqualTo(Instant.parse("2026-10-31T07:00:00Z"));
    }

    // ────────────────────────────────────────────── AC-99.4：夏令时两天

    @Test
    @DisplayName("AC-99.4：2027-03-14 春令时切换，日界仍是当地 00:00（08:00Z）且只切一次")
    void springForwardDayStart() {
        // 切换前 1 分钟：洛杉矶 2027-03-13 23:59 PST → 当天 0 点 08:00Z
        assertThat(zonesAt("2027-03-14T07:59:00Z").dayStart(LA))
                .isEqualTo(Instant.parse("2027-03-13T08:00:00Z"));
        // 切换当天 00:00 PST（尚未跳表）→ 08:00Z
        assertThat(zonesAt("2027-03-14T08:00:00Z").dayStart(LA))
                .isEqualTo(Instant.parse("2027-03-14T08:00:00Z"));
        // 切换后 03:00 PDT：同一天，日界不变（不会重置第二次）
        assertThat(zonesAt("2027-03-14T10:00:00Z").dayStart(LA))
                .isEqualTo(Instant.parse("2027-03-14T08:00:00Z"));
    }

    @Test
    @DisplayName("AC-99.4：2027-11-07 冬令时回拨，日界仍是当地 00:00（07:00Z）且只切一次")
    void fallBackDayStart() {
        // 切换前 1 分钟：洛杉矶 2027-11-06 23:59 PDT → 当天 0 点 07:00Z
        assertThat(zonesAt("2027-11-07T06:59:00Z").dayStart(LA))
                .isEqualTo(Instant.parse("2027-11-06T07:00:00Z"));
        // 当天 00:00 PDT（还没回拨）→ 07:00Z
        assertThat(zonesAt("2027-11-07T07:00:00Z").dayStart(LA))
                .isEqualTo(Instant.parse("2027-11-07T07:00:00Z"));
        // 回拨后的 01:00 PST（09:00Z）：仍是同一天，日界不变
        assertThat(zonesAt("2027-11-07T09:00:00Z").dayStart(LA))
                .isEqualTo(Instant.parse("2027-11-07T07:00:00Z"));
        // 次日 00:00 PST → 08:00Z（新的一天只开始一次）
        assertThat(zonesAt("2027-11-08T08:00:00Z").dayStart(LA))
                .isEqualTo(Instant.parse("2027-11-08T08:00:00Z"));
    }

    @Test
    @DisplayName("端口调用方传入 IANA 串：dayStartOf 走同一条回落链")
    void dayStartOfUsesFallbackChain() {
        VocabZones zones = zonesAt("2026-10-31T16:01:00Z");
        assertThat(zones.dayStartOf("America/Los_Angeles")).isEqualTo(Instant.parse("2026-10-31T07:00:00Z"));
        assertThat(zones.dayStartOf("Mars/Base")).isEqualTo(Instant.parse("2026-10-31T16:00:00Z"));
        assertThat(zones.dayStartOf(null)).isEqualTo(Instant.parse("2026-10-31T16:00:00Z"));
    }
}
