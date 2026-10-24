package com.teachloop.app;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T389 / plan §11：把模块级功能开关告诉前端，导航据此决定是否出现。
 *
 * <p>开关关闭＝不注册 vocab 路由、导航不出现（宪法第十五条：不放占位空承诺）。
 * 本端点自身始终存在，否则前端无法区分「关闭」和「接口挂了」。
 */
@RestController
public class FeaturesEndpoint {

    private final Map<String, Boolean> features;

    public FeaturesEndpoint(@Value("${teachloop.vocab.enabled:false}") boolean vocabEnabled) {
        this.features = Map.of("vocab", vocabEnabled);
    }

    @GetMapping("/api/features")
    public Map<String, Boolean> features() {
        return features;
    }
}
