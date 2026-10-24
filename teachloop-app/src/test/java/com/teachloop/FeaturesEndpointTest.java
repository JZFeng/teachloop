package com.teachloop.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T389：前端据 /api/features 决定是否显示 vocab 导航；开关值必须如实透出。 */
class FeaturesEndpointTest {

    @Test
    @DisplayName("开时 vocab=true")
    void enabled() {
        assertThat(new FeaturesEndpoint(true).features()).containsEntry("vocab", true);
    }

    @Test
    @DisplayName("关时 vocab=false（导航不出现，路由不注册）")
    void disabled() {
        assertThat(new FeaturesEndpoint(false).features()).containsEntry("vocab", false);
    }
}
