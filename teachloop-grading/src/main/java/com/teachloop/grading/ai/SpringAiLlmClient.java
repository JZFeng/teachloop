package com.teachloop.grading.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/**
 * Spring AI {@code ChatClient} 版模型调用（任务 T081 的生产实现）。
 *
 * <p>请求形状与 plan §6.1 一致：两条消息（system 前缀 + user 正文）、
 * {@code temperature=0.2}、{@code response_format=json_object}、取 {@code .content()} 等价的
 * 原始文本（用 {@code chatResponse()} 而不是 {@code .entity()}，保证「发给模型的 prompt」
 * 与「我们记录的 prompt」一致）。
 *
 * <p><b>无 Key 不构造客户端、不发网络。</b>ChatClient/OpenAIClient 是懒构建的：Bean 可以
 * 在没有 {@code DEEPSEEK_API_KEY} 时装配成功，第一次 {@link #complete} 才报错。
 */
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient fixedClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final BigDecimal temperature;
    private final Duration timeout;

    private volatile ChatClient lazyClient;

    /** 测试/复用已构建 ChatClient 用。 */
    public SpringAiLlmClient(ChatClient chatClient, String model, BigDecimal temperature, Duration timeout) {
        this.fixedClient = Objects.requireNonNull(chatClient, "chatClient");
        this.apiKey = null;
        this.baseUrl = null;
        this.model = model == null || model.isBlank() ? "deepseek-flash" : model.strip();
        this.temperature = temperature == null ? new BigDecimal("0.2") : temperature;
        this.timeout = timeout == null ? Duration.ofSeconds(180) : timeout;
    }

    /** 生产装配用：首次调用时才构建 Spring AI 客户端。 */
    public SpringAiLlmClient(String apiKey, String baseUrl, String model, BigDecimal temperature,
                             Duration timeout) {
        this.fixedClient = null;
        // 本地模型通常不校验 Authorization；OpenAI SDK 又要求 apiKey 非空，用占位值。
        this.apiKey = apiKey == null || apiKey.isBlank() ? "local-no-key" : apiKey.strip();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.strip();
        this.model = model == null || model.isBlank() ? "deepseek-flash" : model.strip();
        this.temperature = temperature == null ? new BigDecimal("0.2") : temperature;
        this.timeout = timeout == null ? Duration.ofSeconds(180) : timeout;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (request == null || request.system() == null || request.user() == null) {
            throw new LlmCallException("模型请求缺少 system 或 user");
        }
        ChatClient client = client();
        long started = System.nanoTime();
        ChatResponse response;
        try {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(temperature.doubleValue())
                    .timeout(timeout);
            if (request.json()) {
                // 只有判分链路要求 JSON；错题解析是纯文本，强加 response_format 会被模型包一层 JSON 外壳。
                options.responseFormat(OpenAiChatModel.ResponseFormat.builder()
                        .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
                        .build());
            }
            response = client.prompt()
                    .system(request.system())
                    .user(request.user())
                    .options(options)
                    .call()
                    .chatResponse();
        } catch (RuntimeException ex) {
            throw new LlmCallException("Spring AI 调用失败: " + ex.getMessage(), classify(ex), ex);
        }
        long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        if (response == null) {
            throw new LlmCallException("Spring AI 返回空响应", LlmCallException.Kind.EMPTY);
        }
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            throw new LlmCallException("Spring AI 响应缺少生成结果", LlmCallException.Kind.EMPTY);
        }
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new LlmCallException("Spring AI 响应内容为空", LlmCallException.Kind.EMPTY);
        }
        String responseModel = model;
        Integer tokensIn = null;
        Integer tokensOut = null;
        if (response.getMetadata() != null) {
            if (response.getMetadata().getModel() != null && !response.getMetadata().getModel().isBlank()) {
                responseModel = response.getMetadata().getModel();
            }
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                tokensIn = usage.getPromptTokens();
                tokensOut = usage.getCompletionTokens();
            }
        }
        return new LlmResponse(responseModel, content, tokensIn, tokensOut, latencyMs);
    }

    /**
     * D-31：从 Spring AI / OpenAI SDK 的异常链里认类别。
     *
     * <p>SDK 会把 HTTP 错误包装成 {@code com.openai.errors.*}；连接问题可能是
     * {@code OpenAIIoException} 或 {@code SocketTimeoutException}。这里按「先具体、后一般」
     * 遍历 cause 链，认不出的一律 {@code OTHER}（不重试）。
     */
    static LlmCallException.Kind classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof com.openai.errors.RateLimitException) {
                return LlmCallException.Kind.RATE_LIMIT;
            }
            if (t instanceof com.openai.errors.InternalServerException) {
                return LlmCallException.Kind.SERVER;
            }
            if (t instanceof com.openai.errors.UnauthorizedException
                    || t instanceof com.openai.errors.PermissionDeniedException) {
                return LlmCallException.Kind.AUTH;
            }
            if (t instanceof com.openai.errors.BadRequestException
                    || t instanceof com.openai.errors.NotFoundException) {
                return LlmCallException.Kind.BAD_REQUEST;
            }
            if (t instanceof java.net.http.HttpConnectTimeoutException) {
                return LlmCallException.Kind.CONNECT;
            }
            if (t instanceof java.net.http.HttpTimeoutException
                    || t instanceof java.net.SocketTimeoutException) {
                return LlmCallException.Kind.TIMEOUT;
            }
            if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException) {
                return LlmCallException.Kind.CONNECT;
            }
            if (t instanceof com.openai.errors.OpenAIIoException) {
                return LlmCallException.Kind.CONNECT;
            }
        }
        return LlmCallException.Kind.OTHER;
    }

    private ChatClient client() {
        if (fixedClient != null) {
            return fixedClient;
        }
        ChatClient current = lazyClient;
        if (current == null) {
            synchronized (this) {
                current = lazyClient;
                if (current == null) {
                    current = buildClient();
                    lazyClient = current;
                }
            }
        }
        return current;
    }

    private ChatClient buildClient() {
        try {
            SpringAiOpenAiHttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                    .timeout(timeout)
                    .build();
            ClientOptions options = ClientOptions.builder()
                    .httpClient(httpClient)
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .timeout(timeout)
                    // SDK 的隐式重试会让「一次模型调用」对应多条 ai_calls，关掉；重试由引擎控制。
                    .maxRetries(0)
                    .build();
            OpenAIClient openAiClient = new OpenAIClientImpl(options);
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiClient(openAiClient)
                    // 不设 async 客户端时，Spring AI 会用 options 里的凭据另建一个；这里复用同一个。
                    .openAiClientAsync(openAiClient.async())
                    .options(OpenAiChatOptions.builder()
                            .model(model)
                            .temperature(temperature.doubleValue())
                            .build())
                    .build();
            return ChatClient.create(chatModel);
        } catch (RuntimeException ex) {
            throw new LlmCallException("构建 Spring AI ChatClient 失败: " + ex.getMessage(), ex);
        }
    }
}
