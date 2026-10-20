package com.teachloop.grading.ai;

/**
 * 模型调用的最小可替换边界。
 *
 * <p>生产实现用 Spring AI {@code ChatClient}（见 plan §6.2）；当前本地 Maven 仓库无法解析
 * Spring AI（沙箱不能写 {@code ~/.m2}），因此先用 JDK {@code HttpClient} 直连 DeepSeek 的
 * OpenAI 兼容端点。二者实现同一个接口，换实现不影响 {@code AiGradingEngine}。
 */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);

    /** system 与 user 分开传：system 是缓存前缀，user 每份不同。 */
    record LlmRequest(String system, String user) {
    }

    /**
     * @param tokensIn  输入 tokens；模型未返回时为 {@code null}
     * @param tokensOut 输出 tokens；模型未返回时为 {@code null}
     * @param latencyMs 本次 HTTP 调用耗时（毫秒）
     */
    record LlmResponse(String model, String content, Integer tokensIn, Integer tokensOut, long latencyMs) {
    }
}
