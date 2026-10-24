package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * T391 端到端：开关打开时预览接口真的注册、需要登录、并把 cleanups 原样返回。
 * 开关关闭时路由不存在由 T389 的 `/api/features` + `@ConditionalOnProperty` 保证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"teachloop.vocab.enabled=true", "teachloop.vocab.lexicon.enabled=false"})
@Testcontainers
class VocabImportPreviewTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("teachloop.grading.poll-ms", () -> "3600000");
        registry.add("teachloop.grading.dispatch-ms", () -> "3600000");
        registry.add("teachloop.grading.sweep-ms", () -> "3600000");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    Environment env;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("登录后 POST /api/vocab/import/preview：token、去重、清洗记录、跳过行都在响应里")
    void previewReturnsCleanups() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base, "student@teachloop.local", "teachloop123");

        HttpResponse<String> response = post(base, cookie, "/api/vocab/import/preview", Map.of(
                "text", "1. \"take off\", apple n.，brave\n# 说明\napple",
                "language", "en"));
        assertThat(response.statusCode()).isEqualTo(200);
        var body = MAPPER.readTree(response.body());
        assertThat(body.get("distinctWords")).hasSize(3);
        assertThat(body.get("cleanups")).isNotEmpty();
        assertThat(body.get("cleanups").get(0).get("line").asInt()).isEqualTo(1);
        assertThat(body.get("cleanups").toString()).contains("去引号", "去词性标注", "全角标点转半角");
        assertThat(body.get("skipped")).hasSize(1);
        assertThat(body.get("skipped").get(0).get("reason").asString()).contains("标题");
    }

    @Test
    @DisplayName("未登录调用预览接口返回 401")
    void anonymousIsRejected() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        HttpResponse<String> response = post(base, null, "/api/vocab/import/preview",
                Map.of("text", "apple", "language", "en"));
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private String login(String base, String email, String password) throws Exception {
        HttpResponse<String> response = post(base, null, "/api/auth/login",
                Map.of("email", email, "password", password));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow()
                .split(";", 2)[0];
    }

    private HttpResponse<String> post(String base, String cookie, String path, Map<String, ?> body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("Accept-Language", "zh-CN")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
