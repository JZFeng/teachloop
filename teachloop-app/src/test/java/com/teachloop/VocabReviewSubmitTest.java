package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** T405 验收：评分推进 SM-2、日志落库、同幂等键重放不双写、越权 404。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false"
        })
@Testcontainers
class VocabReviewSubmitTest {

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

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("认识：REVIEW + 日志 2 + successes 1；同幂等键重放只落一条、不重复推进")
    void knownAndIdempotent() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        commit(base, cookie, "reviewknown");
        UUID wordId = wordId(userId, "reviewknown");

        JsonNode first = MAPPER.readTree(post(base, cookie, "/api/vocab/review",
                Map.of("wordId", wordId, "rating", "KNOWN", "elapsedMs", 1200), "key-known").body());
        assertThat(first.get("state").asString()).isEqualTo("REVIEW");
        assertThat(first.get("duplicate").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject(
                "select rating from vocab_review_logs where word_id = ?", Integer.class, wordId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select successes from vocab_progress where word_id = ?", Integer.class, wordId))
                .isEqualTo(1);

        JsonNode replay = MAPPER.readTree(post(base, cookie, "/api/vocab/review",
                Map.of("wordId", wordId, "rating", "KNOWN", "elapsedMs", 1200), "key-known").body());
        assertThat(replay.get("duplicate").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_review_logs where word_id = ?", Integer.class, wordId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select reviewed_count from vocab_progress where word_id = ?", Integer.class, wordId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("不认识：LEARNING、5 分钟后、日志 1、lapses+1、successes 归零")
    void dontKnow() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        commit(base, cookie, "reviewunknown");
        UUID wordId = wordId(userId, "reviewunknown");
        jdbc.update("update vocab_progress set successes = 3 where word_id = ?", wordId);

        JsonNode result = MAPPER.readTree(post(base, cookie, "/api/vocab/review",
                Map.of("wordId", wordId, "rating", "DONT_KNOW"), null).body());
        assertThat(result.get("state").asString()).isEqualTo("LEARNING");
        assertThat(jdbc.queryForObject(
                "select rating from vocab_review_logs where word_id = ?", Integer.class, wordId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select successes from vocab_progress where word_id = ?", Integer.class, wordId)).isZero();
        assertThat(jdbc.queryForObject(
                "select lapses from vocab_progress where word_id = ?", Integer.class, wordId)).isEqualTo(1);
    }

    @Test
    @DisplayName("越权：别的用户给这个词评分返回 404")
    void ownership() throws Exception {
        String base = base();
        String studentCookie = login(base, "student@teachloop.local");
        UUID userId = me(base, studentCookie);
        commit(base, studentCookie, "reviewowner");
        UUID wordId = wordId(userId, "reviewowner");
        String teacherCookie = login(base, "teacher@teachloop.local");
        assertThat(post(base, teacherCookie, "/api/vocab/review",
                Map.of("wordId", wordId, "rating", "KNOWN"), "k-owner").statusCode()).isEqualTo(404);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private String commit(String base, String cookie, String text) throws Exception {
        HttpResponse<String> response = post(base, cookie, "/api/vocab/import/commit",
                Map.of("text", text, "language", "en"), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("batchId").asString();
    }

    private UUID wordId(UUID userId, String normalized) {
        return jdbc.queryForObject(
                "select id from vocab_words where user_id = ? and normalized_form = ?",
                UUID.class, userId, normalized);
    }

    private UUID me(String base, String cookie) throws Exception {
        return UUID.fromString(MAPPER.readTree(send(base, cookie, "GET", "/api/auth/me", null, null).body())
                .get("id").asString());
    }

    private HttpResponse<String> post(String base, String cookie, String path, Object body,
                                      String idempotencyKey) throws Exception {
        return send(base, cookie, "POST", path, body, idempotencyKey);
    }

    private HttpResponse<String> send(String base, String cookie, String method, String path,
                                      Object body, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN");
        if (cookie != null) builder.header("Cookie", cookie);
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body == null ? "" : MAPPER.writeValueAsString(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String base, String email) throws Exception {
        HttpResponse<String> response = send(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
