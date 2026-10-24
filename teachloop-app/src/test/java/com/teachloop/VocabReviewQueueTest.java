package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

/** T402 验收：到期升序、排除暂停/软删、每日新卡上限、单次 100、去重、三个计数。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false",
                "teachloop.vocab.review.daily-new-cards=2"
        })
@Testcontainers
class VocabReviewQueueTest {

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
    @DisplayName("新卡上限 2；暂停/软删不进队列；到期卡在前；计数与去重正确；单次上限生效")
    void queueRules() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        commit(base, cookie, "alpha, beta, gamma, delta, epsilon", "Unit 8");
        UUID beta = wordId(userId, "beta");
        UUID gamma = wordId(userId, "gamma");
        jdbc.update("update vocab_words set removed = true where id = ?", beta);
        jdbc.update("update vocab_words set deleted = true, deleted_at = now() where id = ?", gamma);

        JsonNode queue = get(base, cookie, "/api/vocab/review/queue");
        assertThat(queue.get("total").asInt()).isEqualTo(3);
        assertThat(queue.get("items").size()).isEqualTo(2);
        assertThat(queue.get("newToday").asInt()).isEqualTo(2);
        assertThat(queue.get("newLimitReached").asBoolean()).isTrue();
        assertThat(queue.get("todayReviewed").asInt()).isZero();
        assertThat(queue.get("remaining").asInt()).isEqualTo(2);
        List<String> forms = new ArrayList<>();
        queue.get("items").forEach(item -> forms.add(item.get("form").asString()));
        assertThat(forms).doesNotContain("beta", "gamma");

        // 一张到期卡（非 NEW）应排在 NEW 之前
        UUID delta = wordId(userId, "delta");
        jdbc.update("update vocab_progress set state = 'REVIEW', due_at = now() - interval '1 day',"
                + " interval_days = 5 where word_id = ?", delta);
        JsonNode withReview = get(base, cookie, "/api/vocab/review/queue");
        assertThat(withReview.get("total").asInt()).isEqualTo(3);
        assertThat(withReview.get("items").get(0).get("state").asString()).isEqualTo("REVIEW");
        assertThat(withReview.get("newLimitReached").asBoolean()).isFalse();

        // 今日已复习计数来自 review_logs（今天，Asia/Shanghai）
        jdbc.update("""
                insert into vocab_review_logs (user_id, word_id, rating, reviewed_at)
                values (?, ?, 1, now())
                """, userId, delta);
        assertThat(get(base, cookie, "/api/vocab/review/queue").get("todayReviewed").asInt()).isEqualTo(1);

        // limit=1：只取一张，但总数不变；语言隔离
        JsonNode limited = get(base, cookie, "/api/vocab/review/queue?limit=1");
        assertThat(limited.get("items").size()).isEqualTo(1);
        assertThat(limited.get("total").asInt()).isEqualTo(3);
        commit(base, cookie, "苹果", "zh", "zh");
        assertThat(get(base, cookie, "/api/vocab/review/queue").get("total").asInt()).isEqualTo(3);

        // 去重：队列里 id 唯一
        List<String> ids = new ArrayList<>();
        get(base, cookie, "/api/vocab/review/queue").get("items")
                .forEach(item -> ids.add(item.get("id").asString()));
        assertThat(new HashSet<>(ids)).hasSize(ids.size());
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private String commit(String base, String cookie, String text, String label) throws Exception {
        return commit(base, cookie, text, label, "en");
    }

    private String commit(String base, String cookie, String text, String label, String language)
            throws Exception {
        HttpResponse<String> response = post(base, cookie, "/api/vocab/import/commit",
                Map.of("text", text, "language", language, "label", label));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("batchId").asString();
    }

    private UUID wordId(UUID userId, String normalized) {
        return jdbc.queryForObject(
                "select id from vocab_words where user_id = ? and normalized_form = ?",
                UUID.class, userId, normalized);
    }

    private UUID me(String base, String cookie) throws Exception {
        return UUID.fromString(MAPPER.readTree(send(base, cookie, "GET", "/api/auth/me", null).body())
                .get("id").asString());
    }

    private JsonNode get(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, "GET", path, null);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> post(String base, String cookie, String path, Object body)
            throws Exception {
        return send(base, cookie, "POST", path, body);
    }

    private HttpResponse<String> send(String base, String cookie, String method, String path,
                                      Object body) throws Exception {
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

    private String login(String base, String email) throws Exception {
        HttpResponse<String> response = send(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
