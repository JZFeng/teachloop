package com.teachloop.sitting.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T062：359/360/361 秒与 1920/1921 宽边界；拒绝时指出具体超限项。 */
class VideoRulesTest {

    @Test
    @DisplayName("时长边界：359/360 通过，361 拒绝")
    void durationBoundary() {
        assertThat(VideoRules.violations(new VideoProbe.VideoInfo(359, 1920, 1080))).isEmpty();
        assertThat(VideoRules.violations(new VideoProbe.VideoInfo(360, 1920, 1080))).isEmpty();
        assertThat(VideoRules.violations(new VideoProbe.VideoInfo(361, 1280, 720)))
                .anyMatch(e -> e.contains("361") && e.contains("360"));
    }

    @Test
    @DisplayName("宽高边界：1920/1080 通过，1921 宽或 1081 高拒绝")
    void dimensionBoundary() {
        assertThat(VideoRules.violations(new VideoProbe.VideoInfo(10, 1920, 1080))).isEmpty();
        assertThat(VideoRules.violations(new VideoProbe.VideoInfo(10, 1921, 720)))
                .anyMatch(e -> e.contains("宽度 1921") && e.contains("1920"));
        assertThat(VideoRules.violations(new VideoProbe.VideoInfo(10, 1280, 1081)))
                .anyMatch(e -> e.contains("高度 1081") && e.contains("1080"));
    }
}
