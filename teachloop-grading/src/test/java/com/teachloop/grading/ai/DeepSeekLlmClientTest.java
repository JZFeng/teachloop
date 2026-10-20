package com.teachloop.grading.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** T081：用本地假端点验证 DeepSeek OpenAI 兼容请求的形状，不依赖真实网络。 */
class DeepSeekLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            capture(exchange);
            success(exchange);
        });
        server.createContext("/error/chat/completions", exchange -> {
            capture(exchange);
            respond(exchange, 500, "{\"error\":\"boom\"}");
        });
        server.createContext("/bad/chat/completions", exchange -> {
            capture(exchange);
            respond(exchange, 200, "not a json body");
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("请求形状：model / temperature 0.2 / json_object / system+user 两条消息")
    void sendsOpenAiCompatibleRequest() {
        DeepSeekLlmClient client = client("test-key", "");

        LlmClient.LlmResponse response = client.complete(new LlmClient.LlmRequest("SYSTEM PROMPT", "USER BODY"));

        assertThat(response.model()).isEqualTo("deepseek-flash");
        assertThat(response.content()).isEqualTo("{\"ok\":true}");
        assertThat(response.tokensIn()).isEqualTo(321);
        assertThat(response.tokensOut()).isEqualTo(45);
        assertThat(response.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(lastAuthorization.get()).isEqualTo("Bearer test-key");

        JsonNode body = mapper.readTree(lastBody.get());
        assertThat(body.path("model").asString()).isEqualTo("deepseek-flash");
        assertThat(body.path("temperature").decimalValue()).isEqualByComparingTo("0.2");
        assertThat(body.path("response_format").path("type").asString()).isEqualTo("json_object");
        JsonNode messages = body.path("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.path(0).path("role").asString()).isEqualTo("system");
        assertThat(messages.path(0).path("content").asString()).isEqualTo("SYSTEM PROMPT");
        assertThat(messages.path(1).path("role").asString()).isEqualTo("user");
        assertThat(messages.path(1).path("content").asString()).isEqualTo("USER BODY");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("无 API Key 时不发任何网络请求")
    void doesNotCallNetworkWithoutApiKey() {
        DeepSeekLlmClient client = client("", "");

        assertThatThrownBy(() -> client.complete(new LlmClient.LlmRequest("s", "u")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("HTTP 非 2xx 抛 LlmCallException，消息带状态码")
    void rejectsNon2xx() {
        DeepSeekLlmClient client = client("test-key", "/error");

        assertThatThrownBy(() -> client.complete(new LlmClient.LlmRequest("s", "u")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("响应体不是 JSON 时抛 LlmCallException")
    void rejectsNonJsonBody() {
        DeepSeekLlmClient client = client("test-key", "/bad");

        assertThatThrownBy(() -> client.complete(new LlmClient.LlmRequest("s", "u")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    private DeepSeekLlmClient client(String apiKey, String pathPrefix) {
        return new DeepSeekLlmClient(apiKey, "http://127.0.0.1:" + port + pathPrefix, "deepseek-flash",
                new BigDecimal("0.2"), Duration.ofSeconds(5));
    }

    private void capture(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private void success(HttpExchange exchange) throws IOException {
        String body = mapper.writeValueAsString(Map.of(
                "id", "chatcmpl-test",
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", "{\"ok\":true}"),
                        "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 321, "completion_tokens", 45, "total_tokens", 366)));
        respond(exchange, 200, body);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
