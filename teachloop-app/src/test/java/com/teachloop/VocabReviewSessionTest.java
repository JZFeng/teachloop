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

/** T404 后端验收：范围剔除暂停、cursor 续位、认识推进 SRS、S 暂停、结束小结、不计入今日完成数。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false"
        })
@Testcontainers
class VocabReviewSessionTest {

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
    @DisplayName("创建剔除暂停词；认识走 SRS；S 暂停；结束小结；同范围默认续位、可从头")
    void sessionLifecycle() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        commit(base, cookie, "salpha, sbeta, sgamma, sdelta");
        UUID alpha = wordId(userId, "salpha");
        UUID beta = wordId(userId, "sbeta");
        UUID gamma = wordId(userId, "sgamma");
        UUID delta = wordId(userId, "sdelta");
        jdbc.update("update vocab_words set removed = true where id = ?", delta);

        List<UUID> scope = List.of(alpha, beta, gamma, delta);
        JsonNode created = post(base, cookie, "/api/vocab/review-sessions",
                Map.of("wordIds", scope, "fromStart", false));
        UUID sessionId = UUID.fromString(created.get("id").asString());
        assertThat(created.get("total").asInt()).isEqualTo(3);

        JsonNode current = get(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/current");
        assertThat(current.get("id").asString()).isEqualTo(alpha.toString());
        // 别的用例可能已经背过今天（同一个种子账号、同一个库），所以这里比较「前后差值」而不是绝对值
        int reviewedBefore = get(base, cookie, "/api/vocab/review/queue").get("todayReviewed").asInt();

        JsonNode advanced = post(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/advance",
                Map.of("action", "KNOWN"));
        assertThat(advanced.get("cursor").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select state from vocab_progress where word_id = ?", String.class, alpha))
                .isEqualTo("REVIEW");
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_review_logs where session_id = ?", Integer.class, sessionId))
                .isEqualTo(1);
        // 自由复习不计入「今日已复习」
        assertThat(get(base, cookie, "/api/vocab/review/queue").get("todayReviewed").asInt())
                .isEqualTo(reviewedBefore);

        // 同范围再次创建：默认从上次 cursor 续位
        JsonNode resumed = post(base, cookie, "/api/vocab/review-sessions",
                Map.of("wordIds", scope, "fromStart", false));
        UUID resumedId = UUID.fromString(resumed.get("id").asString());
        assertThat(resumed.get("cursor").asInt()).isEqualTo(1);
        assertThat(resumed.get("total").asInt()).isEqualTo(3);
        JsonNode fromStart = post(base, cookie, "/api/vocab/review-sessions",
                Map.of("wordIds", scope, "fromStart", true));
        assertThat(fromStart.get("cursor").asInt()).isZero();

        post(base, cookie, "/api/vocab/review-sessions/" + resumedId + "/advance",
                Map.of("action", "DONT_KNOW"));
        JsonNode paused = post(base, cookie, "/api/vocab/review-sessions/" + resumedId + "/advance",
                Map.of("action", "PAUSE"));
        assertThat(paused.get("cursor").asInt()).isEqualTo(3);
        assertThat(paused.get("done").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject(
                "select removed from vocab_words where id = ?", Boolean.class, gamma)).isTrue();

        JsonNode summary = post(base, cookie, "/api/vocab/review-sessions/" + resumedId + "/end", Map.of());
        assertThat(summary.get("total").asInt()).isEqualTo(3);
        assertThat(summary.get("seen").asInt()).isEqualTo(3);
        assertThat(summary.get("known").asInt()).isZero();
        assertThat(summary.get("unknown").asInt()).isEqualTo(1);
        assertThat(summary.get("paused").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("T436：当前卡带 favorite；SKIP 只推进游标，不记日志、不动排期")
    void skipAndFavorite() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        commit(base, cookie, "sskipone, sskiptwo");
        UUID one = wordId(userId, "sskipone");
        UUID two = wordId(userId, "sskiptwo");
        jdbc.update("update vocab_words set favorite = true where id = ?", one);

        JsonNode created = post(base, cookie, "/api/vocab/review-sessions",
                Map.of("wordIds", List.of(one, two), "fromStart", true));
        UUID sessionId = UUID.fromString(created.get("id").asString());
        JsonNode current = get(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/current");
        assertThat(current.get("favorite").asBoolean()).isTrue();
        assertThat(current.get("paused").asBoolean()).isFalse();

        // T437：暂停是开关——点一次停排期、不推进游标，再点一次恢复（不重置进度）
        JsonNode toggledOn = post(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/advance",
                Map.of("action", "TOGGLE_PAUSE"));
        assertThat(toggledOn.get("cursor").asInt()).isZero();
        assertThat(get(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/current")
                .get("paused").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select removed from vocab_words where id = ?", Boolean.class, one))
                .isTrue();
        post(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/advance",
                Map.of("action", "TOGGLE_PAUSE"));
        assertThat(get(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/current")
                .get("paused").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select removed from vocab_words where id = ?", Boolean.class, one))
                .isFalse();

        JsonNode skipped = post(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/advance",
                Map.of("action", "SKIP"));
        assertThat(skipped.get("cursor").asInt()).isEqualTo(1);
        assertThat(skipped.get("done").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_review_logs where session_id = ?", Integer.class, sessionId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_progress where word_id = ? and state = 'REVIEW'",
                Integer.class, one)).isZero();

        JsonNode summary = post(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/end", Map.of());
        assertThat(summary.get("paused").asInt()).isZero();
    }

    @Test
    @DisplayName("T595：source=today 的会话计入「今日已复习」；走到尾再开一轮从 0 开始；自由复习仍不计入")
    void todaySessionCountsAsReviewed() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        commit(base, cookie, "stodayone, stodaytwo");
        UUID one = wordId(userId, "stodayone");
        UUID two = wordId(userId, "stodaytwo");

        JsonNode created = post(base, cookie, "/api/vocab/review-sessions",
                Map.of("wordIds", List.of(one, two), "fromStart", true, "source", "today"));
        UUID sessionId = UUID.fromString(created.get("id").asString());
        post(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/advance",
                Map.of("action", "KNOWN"));
        // AC-52.2 口径修订：今日复习会话里的评分算「今天背过」
        assertThat(get(base, cookie, "/api/vocab/review/queue").get("todayReviewed").asInt())
                .isEqualTo(1);

        post(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/advance",
                Map.of("action", "KNOWN"));
        assertThat(get(base, cookie, "/api/vocab/review/queue").get("todayReviewed").asInt())
                .isEqualTo(2);

        // 这一轮已经走到尾：同一批词再开会话要从头开始，不能直接给「0 张」的小结
        JsonNode again = post(base, cookie, "/api/vocab/review-sessions",
                Map.of("wordIds", List.of(one, two), "fromStart", false, "source", "today"));
        assertThat(again.get("cursor").asInt()).isZero();

        // 同日自由复习（不带 source）仍不计入今日完成数
        JsonNode free = post(base, cookie, "/api/vocab/review-sessions",
                Map.of("wordIds", List.of(one, two), "fromStart", true));
        UUID freeId = UUID.fromString(free.get("id").asString());
        post(base, cookie, "/api/vocab/review-sessions/" + freeId + "/advance",
                Map.of("action", "KNOWN"));
        assertThat(get(base, cookie, "/api/vocab/review/queue").get("todayReviewed").asInt())
                .isEqualTo(2);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private String commit(String base, String cookie, String text) throws Exception {
        HttpResponse<String> response = postRaw(base, cookie, "/api/vocab/import/commit",
                Map.of("text", text, "language", "en"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private UUID wordId(UUID userId, String normalized) {
        return jdbc.queryForObject(
                "select id from vocab_words where user_id = ? and normalized_form = ?",
                UUID.class, userId, normalized);
    }

    private UUID me(String base, String cookie) throws Exception {
        return UUID.fromString(get(base, cookie, "/api/auth/me").get("id").asString());
    }

    private JsonNode get(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, "GET", path, null);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private JsonNode post(String base, String cookie, String path, Object body) throws Exception {
        HttpResponse<String> response = postRaw(base, cookie, path, body);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> postRaw(String base, String cookie, String path, Object body)
            throws Exception {
        return send(base, cookie, "POST", path, body);
    }

    private HttpResponse<String> send(String base, String cookie, String method, String path,
                                      Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN");
        if (cookie != null) builder.header("Cookie", cookie);
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
