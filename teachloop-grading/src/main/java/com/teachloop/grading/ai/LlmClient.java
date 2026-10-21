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

    /**
     * system 与 user 分开传：system 是缓存前缀，user 每份不同。
     *
     * <p>{@code json} 为 true 时要求模型输出 JSON 对象（判分链路：{@code GradeOutputValidator}
     * 按 JSON 解析）。错题解析是纯文本，必须用 {@link #plain} 关掉——否则模型被强制包成
     * {@code {"解析": "..."}}，学生反馈里会显示这层 JSON 外壳。
     */
    record LlmRequest(String system, String user, boolean json) {

        /** 默认 JSON 模式：判分链路的既有行为。 */
        public LlmRequest(String system, String user) {
            this(system, user, true);
        }

        /** 纯文本模式：错题解析用。 */
        public static LlmRequest plain(String system, String user) {
            return new LlmRequest(system, user, false);
        }
    }

    /**
     * @param tokensIn  输入 tokens；模型未返回时为 {@code null}
     * @param tokensOut 输出 tokens；模型未返回时为 {@code null}
     * @param latencyMs 本次 HTTP 调用耗时（毫秒）
     */
    record LlmResponse(String model, String content, Integer tokensIn, Integer tokensOut, long latencyMs) {
    }
}
