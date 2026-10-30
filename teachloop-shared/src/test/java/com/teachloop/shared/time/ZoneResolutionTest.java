package com.teachloop.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 011 / T557 / AC-99.1 / AC-99.2：时区回落链（头 &gt; 资料 &gt; 产品默认）。
 *
 * <p>纯函数测试：不启 Spring，非法值一律回落、绝不抛异常——时区错了只影响「今天」的起点，
 * 不该让请求 500。
 */
class ZoneResolutionTest {

    private static final ZoneId PRODUCT = ZoneId.of("Asia/Shanghai");

    @Test
    @DisplayName("AC-99.1：请求头优先于资料")
    void headerWins() {
        assertThat(ZoneResolution.resolve("America/Los_Angeles", Optional.of("Europe/London"), PRODUCT))
                .isEqualTo(ZoneId.of("America/Los_Angeles"));
    }

    @Test
    @DisplayName("AC-99.1：头缺失时用资料里的时区")
    void storedWhenHeaderMissing() {
        assertThat(ZoneResolution.resolve(null, Optional.of("Europe/London"), PRODUCT))
                .isEqualTo(ZoneId.of("Europe/London"));
    }

    @Test
    @DisplayName("AC-99.2：非法头回落到资料，而不是产品默认")
    void invalidHeaderFallsBackToStored() {
        assertThat(ZoneResolution.resolve("Mars/Base", Optional.of("Europe/London"), PRODUCT))
                .isEqualTo(ZoneId.of("Europe/London"));
    }

    @Test
    @DisplayName("AC-99.2：头与资料都非法时回落到产品默认")
    void bothInvalidFallBackToProduct() {
        assertThat(ZoneResolution.resolve("Mars/Base", Optional.of("Not/AZone"), PRODUCT))
                .isEqualTo(PRODUCT);
    }

    @Test
    @DisplayName("AC-99.2：空串、纯空格、超长串都按缺失处理")
    void blankAndOversizedFallBack() {
        String tooLong = "America/" + "x".repeat(80);
        assertThat(ZoneResolution.resolve("", Optional.empty(), PRODUCT)).isEqualTo(PRODUCT);
        assertThat(ZoneResolution.resolve("   ", Optional.of("Europe/London"), PRODUCT))
                .isEqualTo(ZoneId.of("Europe/London"));
        assertThat(ZoneResolution.resolve(tooLong, Optional.empty(), PRODUCT)).isEqualTo(PRODUCT);
    }

    @Test
    @DisplayName("AC-99.1：资料为空 Optional 与 null 都等价于「未设置」")
    void emptyStoredMeansUnset() {
        assertThat(ZoneResolution.resolve(null, Optional.empty(), PRODUCT)).isEqualTo(PRODUCT);
        assertThat(ZoneResolution.resolve(null, null, PRODUCT)).isEqualTo(PRODUCT);
    }

    @Test
    @DisplayName("AC-99.4：夏令时两天仍能解析成同一个 IANA 时区（日界由调用方算）")
    void dstDaysKeepSameZone() {
        assertThat(ZoneResolution.resolve("America/Los_Angeles", Optional.empty(), PRODUCT))
                .isEqualTo(ZoneId.of("America/Los_Angeles"));
    }
}
