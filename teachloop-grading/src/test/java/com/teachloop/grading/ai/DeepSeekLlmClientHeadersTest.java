package com.teachloop.grading.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 决策 024：provider 级额外请求头。
 *
 * <p>默认用例用本地 HTTP 服务器断言「头真的发出去了」；带 {@code TEACHLOOP_OPENCODE_KEY}
 * 时额外跑一次 opencode go 的真实调用（网关确实要求 {@code x-opencode-session}，
 * 缺了返回 400 MissingSessionID —— 2026-10-25 实测）。
 */
class DeepSeekLlmClientHeadersTest {

    private HttpServer server;
    private final AtomicReference<String> sessionHeader = new AtomicReference<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private final AtomicReference<String> agentHeader = new AtomicReference<>();
    private final AtomicReference<String> bodySeen = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            sessionHeader.set(exchange.getRequestHeaders().getFirst("x-opencode-session"));
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            agentHeader.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            bodySeen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = ("{\"model\":\"stub\",\"choices\":[{\"message\":{\"content\":"
                    + "\"{\\\"total\\\":8}\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsConfiguredExtraHeaders() {
        String session = UUID.randomUUID().toString();
        DeepSeekLlmClient client = new DeepSeekLlmClient("sk-test", base(), "stub-model",
                new BigDecimal("0.2"), Duration.ofSeconds(20),
                Map.of("x-opencode-session", session, "User-Agent", "teachloop/1.0"));

        LlmClient.LlmResponse response = client.complete(new LlmClient.LlmRequest("system", "user"));

        assertThat(sessionHeader.get()).isEqualTo(session);
        assertThat(agentHeader.get()).isEqualTo("teachloop/1.0");
        assertThat(authHeader.get()).isEqualTo("Bearer sk-test");
        assertThat(bodySeen.get()).contains("\"model\":\"stub-model\"");
        assertThat(response.content()).contains("total");
    }

    @Test
    void extraHeadersCannotOverrideAuthorizationOrContentType() {
        DeepSeekLlmClient client = new DeepSeekLlmClient("sk-real", base(), "stub-model",
                new BigDecimal("0.2"), Duration.ofSeconds(20),
                Map.of("authorization", "Bearer hacked", "content-type", "text/plain"));

        client.complete(new LlmClient.LlmRequest("system", "user"));

        assertThat(authHeader.get()).isEqualTo("Bearer sk-real");
    }

    @Test
    void worksWithoutExtraHeaders() {
        DeepSeekLlmClient client = new DeepSeekLlmClient("sk-test", base(), "stub-model",
                new BigDecimal("0.2"), Duration.ofSeconds(20));

        client.complete(new LlmClient.LlmRequest("system", "user"));

        assertThat(sessionHeader.get()).isNull();
        assertThat(authHeader.get()).isEqualTo("Bearer sk-test");
    }

    /** 真实 opencode go 调用：只有显式给 key 时才跑，避免默认构建打外网。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "TEACHLOOP_OPENCODE_KEY", matches = ".+")
    void realOpencodeCallWithSessionHeader() {
        DeepSeekLlmClient client = new DeepSeekLlmClient(
                System.getenv("TEACHLOOP_OPENCODE_KEY"),
                "https://opencode.ai/inference/go/openai/v1",
                "deepseek-v4.1-flash",
                new BigDecimal("0.2"), Duration.ofSeconds(60),
                Map.of("x-opencode-session", UUID.randomUUID().toString(),
                        "User-Agent", "teachloop/1.0"));

        LlmClient.LlmResponse response = client.complete(
                LlmClient.LlmRequest.plain("You are a grader.", "Reply with the single word OK."));

        assertThat(response.content()).isNotBlank();
        assertThat(response.model()).isNotBlank();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }
}
