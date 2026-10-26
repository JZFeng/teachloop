package com.teachloop.identity.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 通用问题：用户常把「完整接口地址」当 base URL 填（2026-10-25 实测踩到）。归一化后两种都能用。 */
class AiProviderServiceBaseUrlTest {

    @Test
    void stripsChatCompletionsSuffix() {
        assertThat(AiProviderService.normalizeBaseUrl(
                "https://opencode.ai/inference/go/openai/v1/chat/completions"))
                .isEqualTo("https://opencode.ai/inference/go/openai/v1");
    }

    @Test
    void stripsModelsSuffixAndTrailingSlashes() {
        assertThat(AiProviderService.normalizeBaseUrl("https://api.deepseek.com/v1/models/"))
                .isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    void keepsCleanBaseUrlUntouched() {
        assertThat(AiProviderService.normalizeBaseUrl("http://192.168.68.101:8080/v1"))
                .isEqualTo("http://192.168.68.101:8080/v1");
    }

    @Test
    void isIdempotentAndHandlesBlank() {
        String once = AiProviderService.normalizeBaseUrl("https://x.dev/v1/completions/");
        assertThat(once).isEqualTo("https://x.dev/v1");
        assertThat(AiProviderService.normalizeBaseUrl(once)).isEqualTo(once);
        assertThat(AiProviderService.normalizeBaseUrl(null)).isEmpty();
    }
}
