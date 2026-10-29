package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.content.ContentStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** T553 验收：偏好生效——用户配额被硬上限压住，三种策略只改顺序（AC-88.3 / 88.4）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false",
                "teachloop.grading.poll-ms=3600000",
                "teachloop.grading.dispatch-ms=3600000",
                "teachloop.grading.sweep-ms=3600000"
        })
@Testcontainers
class VocabPrefsTest {

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
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ContentStore contentStore;

    @Test
    @DisplayName("PUT 偏好后：队列按用户配额出卡；REVIEW_FIRST 把到期卡排在前面")
    void prefsDriveQueue() throws Exception {
        String base = base();
        String cookie = login(base);
        UUID userId = UUID.fromString(MAPPER.readTree(
                get(base, cookie, "/api/auth/me").body()).get("id").asString());

        commit(base, cookie, "alpha, beta, gamma, delta, epsilon, zeta");
        seedContent(userId, List.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta"));
        // 其中两张变成「到期复习卡」，其余是 NEW
        for (String form : List.of("alpha", "beta")) {
            jdbc.update("update vocab_progress set state = 'REVIEW', due_at = now() - interval '1 day'"
                    + " where word_id = (select id from vocab_words where user_id = ? and normalized_form = ?)",
                    userId, form);
        }

        JsonNode saved = putJson(base, cookie, "/api/vocab/review-prefs", Map.of(
                "dailyNewCards", 3,
                "strategy", "REVIEW_FIRST",
                "blocks", List.of("examples", "core"),
                "hidden", List.of("antonyms")));
        assertThat(saved.get("dailyNewCards").asInt()).isEqualTo(3);
        assertThat(saved.get("strategy").asString()).isEqualTo("REVIEW_FIRST");
        assertThat(saved.get("blocks").size()).isEqualTo(8);   // 缺失 key 补齐
        assertThat(saved.get("blocks").get(0).asString()).isEqualTo("examples");

        JsonNode queue = MAPPER.readTree(get(base, cookie, "/api/vocab/review/queue").body());
        assertThat(queue.get("newToday").asInt()).isLessThanOrEqualTo(3);
        // REVIEW_FIRST：到期卡（state=REVIEW）排在 NEW 前面
        String firstState = queue.get("items").get(0).get("state").asString();
        assertThat(firstState).isEqualTo("REVIEW");

        // 非法值被纠正而不是 500
        JsonNode fixed = putJson(base, cookie, "/api/vocab/review-prefs", Map.of(
                "dailyNewCards", -1, "strategy", "nonsense", "blocks", List.of("bogus"), "hidden",
                List.of("bogus")));
        assertThat(fixed.get("dailyNewCards").asInt()).isEqualTo(3);
        assertThat(fixed.get("strategy").asString()).isEqualTo("MIXED");
        assertThat(fixed.get("hidden").size()).isZero();
    }

    private void seedContent(UUID userId, List<String> forms) {
        for (String form : forms) {
            String json = "{\"senses\":[{\"pos\":\"noun\",\"zh\":\"译\"}],"
                    + "\"examples\":[{\"en\":\"A " + form + " here.\",\"zh\":\"例句\"}]}";
            long contentId = contentStore.put("en", form, json, "TEST");
            jdbc.update("update vocab_words set content_id = ?, content_status = 'READY'"
                    + " where user_id = ? and normalized_form = ?", contentId, userId, form);
        }
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private void commit(String base, String cookie, String text) throws Exception {
        HttpResponse<String> response = post(base, cookie, "/api/vocab/import/commit",
                Map.of("text", text, "language", "en", "label", "Prefs unit"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private JsonNode putJson(String base, String cookie, String path, Object body) throws Exception {
        HttpResponse<String> response = send(base, cookie, "PUT", path, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> post(String base, String cookie, String path, Object body)
            throws Exception {
        return send(base, cookie, "POST", path, body);
    }

    private HttpResponse<String> get(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, "GET", path, null);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return response;
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
                    .method(method, HttpRequest.BodyPublishers.ofString(
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
