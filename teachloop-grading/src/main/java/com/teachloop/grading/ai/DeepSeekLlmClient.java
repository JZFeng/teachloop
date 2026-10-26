package com.teachloop.grading.ai;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DeepSeek 的 OpenAI 兼容端点（{@code POST {base-url}/chat/completions}）。
 *
 * <p>为什么不是 Spring AI：本机 {@code ~/.m2} 在沙箱内不可写，Spring AI 依赖无法落盘。
 * 这里保持与 plan §6.1 相同的请求形状（model / temperature 0.2 / json_object / 两条消息），
 * 并用 {@code .content()} 等价的「取原始字符串 + 自己校验」策略，不用结构化输出 API。
 */
public class DeepSeekLlmClient implements LlmClient {

    private static final int ERROR_BODY_LIMIT = 2000;

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final BigDecimal temperature;
    private final Duration timeout;
    private final HttpClient http;
    private final java.util.Map<String, String> extraHeaders;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepSeekLlmClient(String apiKey, String baseUrl, String model, BigDecimal temperature,
                             Duration timeout) {
        this(apiKey, baseUrl, model, temperature, timeout, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /** 决策 024：带 provider 级额外请求头（如 opencode go 的 x-opencode-session）。 */
    public DeepSeekLlmClient(String apiKey, String baseUrl, String model, BigDecimal temperature,
                             Duration timeout, java.util.Map<String, String> extraHeaders) {
        this(apiKey, baseUrl, model, temperature, timeout, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), extraHeaders);
    }

    DeepSeekLlmClient(String apiKey, String baseUrl, String model, BigDecimal temperature,
                      Duration timeout, HttpClient http) {
        this(apiKey, baseUrl, model, temperature, timeout, http, java.util.Map.of());
    }

    DeepSeekLlmClient(String apiKey, String baseUrl, String model, BigDecimal temperature,
                      Duration timeout, HttpClient http, java.util.Map<String, String> extraHeaders) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.strip();
        this.model = model == null || model.isBlank() ? "deepseek-flash" : model.strip();
        this.temperature = temperature == null ? new BigDecimal("0.2") : temperature;
        this.timeout = timeout == null ? Duration.ofSeconds(180) : timeout;
        this.http = http;
        this.extraHeaders = extraHeaders == null ? java.util.Map.of() : java.util.Map.copyOf(extraHeaders);
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String body = requestBody(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri())
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        // 决策 024：provider 级额外头。鉴权与内容类型由上面统一管理，这里不允许覆盖。
        extraHeaders.forEach((name, value) -> {
            if (name == null || name.isBlank()) {
                return;
            }
            String header = name.strip();
            if ("authorization".equalsIgnoreCase(header) || "content-type".equalsIgnoreCase(header)) {
                return;
            }
            try {
                builder.header(header, value == null ? "" : value);
            } catch (IllegalArgumentException restricted) {
                // Host/Content-Length 等受限头直接跳过，不让配置把整次调用搞挂
            }
        });
        HttpRequest httpRequest = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("模型调用被中断", LlmCallException.Kind.OTHER, ex);
        } catch (IOException ex) {
            throw new LlmCallException("模型调用失败: " + ex.getMessage(), classifyIo(ex), ex);
        }
        long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new LlmCallException("模型返回 HTTP " + response.statusCode() + ": "
                    + truncate(response.body()), classifyStatus(response.statusCode()), null,
                    retryAfterSeconds(response));
        }
        return parseBody(response.body(), latencyMs);
    }

    /** D-31：HTTP 状态 → 重试/熔断分类。 */
    static LlmCallException.Kind classifyStatus(int status) {
        if (status == 429) {
            return LlmCallException.Kind.RATE_LIMIT;
        }
        if (status >= 500) {
            return LlmCallException.Kind.SERVER;
        }
        if (status == 401 || status == 403) {
            return LlmCallException.Kind.AUTH;
        }
        if (status >= 400) {
            return LlmCallException.Kind.BAD_REQUEST;
        }
        return LlmCallException.Kind.OTHER;
    }

    /** D-31：连接超时/拒绝连接/域名解析算可重试；读超时算不可重试。 */
    static LlmCallException.Kind classifyIo(IOException ex) {
        if (ex instanceof java.net.http.HttpConnectTimeoutException) {
            return LlmCallException.Kind.CONNECT;
        }
        if (ex instanceof java.net.http.HttpTimeoutException) {
            return LlmCallException.Kind.TIMEOUT;
        }
        if (ex instanceof java.net.ConnectException || ex instanceof java.net.UnknownHostException) {
            return LlmCallException.Kind.CONNECT;
        }
        Throwable cause = ex.getCause();
        if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException) {
            return LlmCallException.Kind.CONNECT;
        }
        return LlmCallException.Kind.OTHER;
    }

    private static Long retryAfterSeconds(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            try {
                return java.util.Optional.of(Long.parseLong(value.trim()));
            } catch (NumberFormatException ex) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }

    private URI uri() {
        String base = baseUrl.replaceAll("/+$", "");
        return URI.create(base + "/chat/completions");
    }

    private String requestBody(LlmRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", temperature);
        if (request.json()) {
            // 只有判分链路要求 JSON；错题解析是纯文本，强加 response_format 会被模型包一层 JSON 外壳。
            payload.put("response_format", Map.of("type", "json_object"));
        }
        payload.put("messages", List.of(
                Map.of("role", "system", "content", request.system()),
                Map.of("role", "user", "content", request.user())));
        return mapper.writeValueAsString(payload);
    }

    private LlmResponse parseBody(String body, long latencyMs) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException ex) {
            throw new LlmCallException("模型响应不是合法 JSON: " + truncate(body), LlmCallException.Kind.EMPTY, ex);
        }
        if (root == null || !root.isObject()) {
            throw new LlmCallException("模型响应不是 JSON 对象: " + truncate(body), LlmCallException.Kind.EMPTY);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new LlmCallException("模型响应缺少 choices: " + truncate(body), LlmCallException.Kind.EMPTY);
        }
        JsonNode contentNode = choices.path(0).path("message").path("content");
        String content = contentNode.isString() ? contentNode.asString() : "";
        if (content.isBlank()) {
            throw new LlmCallException("模型响应内容为空: " + truncate(body), LlmCallException.Kind.EMPTY);
        }
        JsonNode usage = root.path("usage");
        Integer tokensIn = usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asInt() : null;
        Integer tokensOut = usage.path("completion_tokens").isNumber() ? usage.path("completion_tokens").asInt() : null;
        return new LlmResponse(model, content, tokensIn, tokensOut, latencyMs);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= ERROR_BODY_LIMIT ? value : value.substring(0, ERROR_BODY_LIMIT) + "...";
    }
}
