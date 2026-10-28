package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

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

/** T397 后端验收：暂停/恢复/删除语义、批量、统计、来源聚合、列配置按学生保存、越权 404。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false"
        })
@Testcontainers
class VocabWordActionsTest {

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
    @DisplayName("暂停不删词、恢复回新词、删除软删且列表/统计消失；历史进度保留")
    void pauseResumeDelete() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        commit(base, cookie, "alpha, beta, gamma", "Unit 5");

        JsonNode stats = get(base, cookie, "/api/vocab/words/stats");
        assertThat(stats.get("total").asInt()).isEqualTo(3);
        assertThat(stats.get("newWords").asInt()).isEqualTo(3);
        assertThat(stats.get("dueToday").asInt()).isEqualTo(3);

        UUID alpha = wordId(userId, "alpha");
        assertThat(post(base, cookie, "/api/vocab/words/" + alpha + "/pause", null).statusCode())
                .isEqualTo(200);
        assertThat(get(base, cookie, "/api/vocab/words/stats").get("paused").asInt()).isEqualTo(1);
        assertThat(get(base, cookie, "/api/vocab/words?paused=true").get("total").asInt()).isEqualTo(1);
        // 暂停词仍在全量列表里
        assertThat(get(base, cookie, "/api/vocab/words").get("total").asInt()).isEqualTo(3);

        assertThat(post(base, cookie, "/api/vocab/words/" + alpha + "/resume", null).statusCode())
                .isEqualTo(200);
        assertThat(get(base, cookie, "/api/vocab/words/stats").get("paused").asInt()).isZero();
        assertThat(jdbc.queryForObject(
                "select state from vocab_progress where word_id = ?", String.class, alpha))
                .isEqualTo("NEW");

        UUID beta = wordId(userId, "beta");
        HttpResponse<String> deleted = delete(base, cookie, "/api/vocab/words/" + beta);
        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(get(base, cookie, "/api/vocab/words/stats").get("total").asInt()).isEqualTo(2);
        assertThat(get(base, cookie, "/api/vocab/words?q=beta").get("total").asInt()).isZero();
        // 软删除：行还在，历史进度也在
        assertThat(jdbc.queryForObject("select deleted from vocab_words where id = ?", Boolean.class, beta))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_progress where word_id = ?", Integer.class, beta)).isEqualTo(1);
    }

    @Test
    @DisplayName("批量暂停、非法动作 400、越权 404、来源聚合、列配置按学生保存")
    void batchOwnershipPrefsSources() throws Exception {
        String base = base();
        String studentCookie = login(base, "student@teachloop.local");
        UUID studentId = me(base, studentCookie);
        commit(base, studentCookie, "delta, eager", "Unit 6");
        List<UUID> ids = List.of(wordId(studentId, "delta"), wordId(studentId, "eager"));

        JsonNode batch = MAPPER.readTree(post(base, studentCookie, "/api/vocab/words/batch",
                Map.of("ids", ids, "action", "PAUSE")).body());
        assertThat(batch.get("affected").asInt()).isEqualTo(2);
        assertThat(get(base, studentCookie, "/api/vocab/words/stats").get("paused").asInt()).isEqualTo(2);
        assertThat(post(base, studentCookie, "/api/vocab/words/batch",
                Map.of("ids", ids, "action", "NOPE")).statusCode()).isEqualTo(400);

        JsonNode sources = get(base, studentCookie, "/api/vocab/sources");
        assertThat(sources.toString()).contains("Unit 6");

        JsonNode prefs = MAPPER.readTree(put(base, studentCookie, "/api/vocab/prefs",
                Map.of("tableColumns", Map.of("order", List.of("form", "state")))).body());
        assertThat(prefs.get("tableColumns").get("order").get(1).asString()).isEqualTo("state");
        assertThat(get(base, studentCookie, "/api/vocab/prefs").get("tableColumns")
                .get("order").get(0).asString()).isEqualTo("form");

        // 教师不能改学生的词（404），且自己的 prefs 是空的
        String teacherCookie = login(base, "teacher@teachloop.local");
        assertThat(post(base, teacherCookie, "/api/vocab/words/" + ids.get(0) + "/pause", null)
                .statusCode()).isEqualTo(404);
        assertThat(get(base, teacherCookie, "/api/vocab/prefs").get("tableColumns").isObject()).isTrue();
    }


    @Test
    @DisplayName("收藏是纯标记：不改变进度/排期；已收藏筛选命中；越权 404")
    void favoriteIsPureMark() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        commit(base, cookie, "favoriteword", "Unit 7");
        UUID wordId = wordId(userId, "favoriteword");
        String beforeState = jdbc.queryForObject(
                "select state from vocab_progress where word_id = ?", String.class, wordId);
        var beforeDue = jdbc.queryForObject(
                "select due_at from vocab_progress where word_id = ?", java.sql.Timestamp.class, wordId);

        assertThat(put(base, cookie, "/api/vocab/words/" + wordId + "/favorite",
                Map.of("favorite", true)).statusCode()).isEqualTo(200);
        assertThat(get(base, cookie, "/api/vocab/words?favorite=true").get("total").asInt()).isEqualTo(1);
        // AC-59.12（T512）：统计卡第五张「已收藏」的数字来自同一句统计 SQL
        assertThat(get(base, cookie, "/api/vocab/words/stats").get("favorite").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select state from vocab_progress where word_id = ?", String.class, wordId))
                .isEqualTo(beforeState);
        assertThat(jdbc.queryForObject(
                "select due_at from vocab_progress where word_id = ?", java.sql.Timestamp.class, wordId))
                .isEqualTo(beforeDue);

        // AC-59.12：暂停只动 removed，不动 favorite——两张卡的数字互不串
        assertThat(post(base, cookie, "/api/vocab/words/" + wordId + "/pause", null).statusCode())
                .isEqualTo(200);
        assertThat(get(base, cookie, "/api/vocab/words/stats").get("favorite").asInt()).isEqualTo(1);
        assertThat(post(base, cookie, "/api/vocab/words/" + wordId + "/resume", null).statusCode())
                .isEqualTo(200);

        String teacherCookie = login(base, "teacher@teachloop.local");
        assertThat(put(base, teacherCookie, "/api/vocab/words/" + wordId + "/favorite",
                Map.of("favorite", false)).statusCode()).isEqualTo(404);

        assertThat(put(base, cookie, "/api/vocab/words/" + wordId + "/favorite",
                Map.of("favorite", false)).statusCode()).isEqualTo(200);
        assertThat(get(base, cookie, "/api/vocab/words?favorite=true").get("total").asInt()).isZero();
        assertThat(get(base, cookie, "/api/vocab/words/stats").get("favorite").asInt()).isZero();
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private String commit(String base, String cookie, String text, String label) throws Exception {
        HttpResponse<String> response = post(base, cookie, "/api/vocab/import/commit",
                Map.of("text", text, "language", "en", "label", label));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("batchId").asString();
    }

    private UUID wordId(UUID userId, String normalized) {
        return jdbc.queryForObject(
                "select id from vocab_words where user_id = ? and normalized_form = ?",
                UUID.class, userId, normalized);
    }

    private UUID me(String base, String cookie) throws Exception {
        HttpResponse<String> response = send(base, cookie, "GET", "/api/auth/me", null);
        return UUID.fromString(MAPPER.readTree(response.body()).get("id").asString());
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

    private HttpResponse<String> put(String base, String cookie, String path, Object body)
            throws Exception {
        return send(base, cookie, "PUT", path, body);
    }

    private HttpResponse<String> delete(String base, String cookie, String path) throws Exception {
        return send(base, cookie, "DELETE", path, null);
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
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(
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
