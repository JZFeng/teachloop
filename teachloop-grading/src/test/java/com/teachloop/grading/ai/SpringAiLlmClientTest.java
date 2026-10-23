package com.teachloop.grading.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T081：Spring AI ChatClient 版的请求参数与响应解析。
 *
 * <p>两个层次：mock ChatModel 断言「我们传了什么」；本地 HttpServer 走真正的 openai-java
 * 客户端，验证 Spring AI 2.0.1 在 Boot 4.1 / Jackson 3 环境里能实际发出 OpenAI 兼容请求。
 */
class SpringAiLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("请求参数：system/user 两条消息 + model + temperature 0.2 + json_object")
    void sendsSystemUserAndJsonOptions() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .model("deepseek-flash").temperature(0.2).build());
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .model("deepseek-flash").temperature(0.2).build());
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("{\"ok\":true}", 11, 7));
        ChatClient chatClient = ChatClient.create(chatModel);
        SpringAiLlmClient client = new SpringAiLlmClient(chatClient, "deepseek-flash",
                new BigDecimal("0.2"), Duration.ofSeconds(180));

        LlmClient.LlmResponse response = client.complete(new LlmClient.LlmRequest("SYSTEM PROMPT", "USER BODY"));

        assertThat(response.content()).isEqualTo("{\"ok\":true}");
        assertThat(response.tokensIn()).isEqualTo(11);
        assertThat(response.tokensOut()).isEqualTo(7);
        assertThat(response.model()).isEqualTo("deepseek-flash");
        assertThat(response.latencyMs()).isGreaterThanOrEqualTo(0);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        assertThat(prompt.getSystemMessage().getText()).isEqualTo("SYSTEM PROMPT");
        assertThat(prompt.getUserMessage().getText()).isEqualTo("USER BODY");
        assertThat(prompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getModel()).isEqualTo("deepseek-flash");
        assertThat(options.getTemperature()).isEqualTo(0.2);
        assertThat(options.getResponseFormat().getType())
                .isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(180));
    }

    @Test
    @DisplayName("无 API Key 时用占位 Key 发请求，本地模型可忽略鉴权")
    void callsLocalEndpointWithPlaceholderKey() {
        SpringAiLlmClient client = new SpringAiLlmClient("", "http://127.0.0.1:" + port,
                "deepseek-flash", new BigDecimal("0.2"), Duration.ofSeconds(5));

        LlmClient.LlmResponse response = client.complete(new LlmClient.LlmRequest("s", "u"));

        assertThat(response.content()).isEqualTo("{\"ok\":true}");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer local-no-key");
    }

    @Test
    @DisplayName("真实 openai-java 客户端对本地假端点：请求/响应均兼容")
    void realClientAgainstLocalEndpoint() {
        SpringAiLlmClient client = new SpringAiLlmClient("test-key", "http://127.0.0.1:" + port,
                "deepseek-flash", new BigDecimal("0.2"), Duration.ofSeconds(10));

        LlmClient.LlmResponse response = client.complete(new LlmClient.LlmRequest("SYSTEM", "USER"));

        assertThat(response.content()).isEqualTo("{\"ok\":true}");
        assertThat(response.tokensIn()).isEqualTo(11);
        assertThat(response.tokensOut()).isEqualTo(7);
        assertThat(response.model()).isEqualTo("deepseek-flash");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer test-key");
        assertThat(lastPath.get()).isEqualTo("/chat/completions");

        JsonNode body = mapper.readTree(lastBody.get());
        assertThat(body.path("model").asString()).isEqualTo("deepseek-flash");
        assertThat(body.path("temperature").decimalValue()).isEqualByComparingTo("0.2");
        assertThat(body.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(body.path("messages").size()).isEqualTo(2);
        assertThat(body.path("messages").path(0).path("role").asString()).isEqualTo("system");
        assertThat(body.path("messages").path(0).path("content").asString()).isEqualTo("SYSTEM");
        assertThat(body.path("messages").path(1).path("role").asString()).isEqualTo("user");
        assertThat(body.path("messages").path(1).path("content").asString()).isEqualTo("USER");
    }

    @Test
    @DisplayName("ChatModel 抛错时包装成 LlmCallException")
    void wrapsChatModelFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("model down"));
        SpringAiLlmClient client = new SpringAiLlmClient(ChatClient.create(chatModel), "deepseek-flash",
                new BigDecimal("0.2"), Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.complete(new LlmClient.LlmRequest("s", "u")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("Spring AI 调用失败");
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastPath.set(exchange.getRequestURI().getPath());
        String body = mapper.writeValueAsString(Map.of(
                "id", "chatcmpl-test",
                "object", "chat.completion",
                "created", 1_710_000_000,
                "model", "deepseek-flash",
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", "{\"ok\":true}"),
                        "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18)));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static ChatResponse chatResponse(String content, int tokensIn, int tokensOut) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(content))))
                .metadata(ChatResponseMetadata.builder()
                        .model("deepseek-flash")
                        .usage(new DefaultUsage(tokensIn, tokensOut))
                        .build())
                .build();
    }
}
