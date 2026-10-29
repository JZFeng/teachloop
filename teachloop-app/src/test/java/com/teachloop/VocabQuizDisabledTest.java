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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** AC-87.10：开关关闭时端点 404、/api/vocab/config 返回 quiz=false，而 Controller 仍然注册。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.quiz.enabled=false",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false",
                "teachloop.grading.poll-ms=3600000",
                "teachloop.grading.dispatch-ms=3600000",
                "teachloop.grading.sweep-ms=3600000"
        })
@Testcontainers
class VocabQuizDisabledTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired
    Environment env;

    @Test
    @DisplayName("关闭时：创建卷 404；config 的 quiz=false")
    void disabled() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base);
        assertThat(post(base, cookie, "/api/vocab/quizzes", Map.of("kind", "TODAY")).statusCode())
                .isEqualTo(404);
        JsonNode config = MAPPER.readTree(get(base, cookie, "/api/vocab/config").body());
        assertThat(config.get("quiz").asBoolean()).isFalse();
    }

    private HttpResponse<String> get(String base, String cookie, String path) throws Exception {
        return send(base, cookie, "GET", path, null);
    }

    private HttpResponse<String> post(String base, String cookie, String path, Object body) throws Exception {
        return send(base, cookie, "POST", path, body);
    }

    private HttpResponse<String> send(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN");
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body == null ? "" : MAPPER.writeValueAsString(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String base) throws Exception {
        HttpResponse<String> response = send(base, null, "POST", "/api/auth/login",
                Map.of("email", "student@teachloop.local", "password", "teachloop123"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
