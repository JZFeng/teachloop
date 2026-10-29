package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.content.ContentStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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

/** T548 验收：出卷、答案不下发、判分、幂等、重考、他人 404（US-87 / AC-87.4 / 87.6 / 87.11）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.quiz.enabled=true",
                "teachloop.vocab.quiz.size.today=5",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false",
                "teachloop.grading.poll-ms=3600000",
                "teachloop.grading.dispatch-ms=3600000",
                "teachloop.grading.sweep-ms=3600000"
        })
@Testcontainers
class VocabQuizTest {

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
    @DisplayName("今日复习过的词出卷：未交卷不下发答案；交卷判分；重复交卷幂等；可以重考错题")
    void createSubmitRetry() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);

        List<String> forms = List.of("induce", "abandon", "dwell", "glance", "struggle");
        commit(base, cookie, String.join(", ", forms), "Quiz unit");
        seedContent(userId, forms);
        // 今日复习记录 = 小测的词源
        for (String form : forms) {
            jdbc.update("""
                    insert into vocab_review_logs (user_id, word_id, rating, reviewed_at)
                    values (?, ?, 2, now())
                    """, userId, wordId(userId, form));
        }

        JsonNode quiz = postJson(base, cookie, "/api/vocab/quizzes", Map.of("kind", "TODAY"));
        assertThat(quiz.get("itemCount").asInt()).isBetween(3, 5);
        assertThat(quiz.get("status").asString()).isEqualTo("IN_PROGRESS");
        JsonNode items = quiz.get("items");
        assertThat(items.size()).isEqualTo(quiz.get("itemCount").asInt());
        // AC-87.4：未交卷不下发答案，也不下发对错
        items.forEach(item -> {
            assertThat(item.get("answer").isNull()).isTrue();
            assertThat(item.get("correct").isNull()).isTrue();
        });
        assertThat(items.get(0).get("stem").asString()).contains("____");

        // 全部按正确答案作答（服务端才知道答案，这里用 SQL 读出来模拟「全对」）
        UUID quizId = UUID.fromString(quiz.get("id").asString());
        List<Map<String, Object>> answers = new ArrayList<>();
        for (JsonNode item : items) {
            UUID itemId = UUID.fromString(item.get("id").asString());
            String key = jdbc.queryForObject("select answer_key from vocab_quiz_items where id = ?",
                    String.class, itemId);
            answers.add(Map.of("itemId", itemId, "given", key));
        }
        JsonNode submitted = postJson(base, cookie, "/api/vocab/quizzes/" + quizId + "/submit",
                Map.of("answers", answers));
        assertThat(submitted.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submitted.get("correctCount").asInt()).isEqualTo(items.size());
        // 交卷后下发正确答案供结果页解析
        assertThat(submitted.get("items").get(0).get("answer").isNull()).isFalse();

        // 幂等：重复交卷返回同一份结果，不重算
        JsonNode again = postJson(base, cookie, "/api/vocab/quizzes/" + quizId + "/submit",
                Map.of("answers", List.of()));
        assertThat(again.get("correctCount").asInt()).isEqualTo(items.size());

        // 复习错题：全对时没有错题可重考
        HttpResponse<String> noWrong = post(base, cookie, "/api/vocab/quizzes/" + quizId + "/retry", Map.of());
        assertThat(noWrong.statusCode()).isEqualTo(400);

        // 另一个人看不到这张卷（AC-87.11）
        String other = login(base, "teacher@teachloop.local");
        HttpResponse<String> peek = getRaw(base, other, "/api/vocab/quizzes/" + quizId);
        assertThat(peek.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("填空接受同词族任意词形（struggled 的题填 struggle 也算对）")
    void fillBlankAcceptsFamilyForm() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        List<String> forms = List.of("induce", "abandon", "dwell", "glance", "struggle");
        commit(base, cookie, String.join(", ", forms), "Quiz unit 2");
        seedContent(userId, forms);
        for (String form : forms) {
            jdbc.update("""
                    insert into vocab_review_logs (user_id, word_id, rating, reviewed_at)
                    values (?, ?, 2, now())
                    """, userId, wordId(userId, form));
        }
        JsonNode quiz = postJson(base, cookie, "/api/vocab/quizzes", Map.of("kind", "TODAY"));
        UUID quizId = UUID.fromString(quiz.get("id").asString());

        List<Map<String, Object>> answers = new ArrayList<>();
        for (JsonNode item : quiz.get("items")) {
            UUID itemId = UUID.fromString(item.get("id").asString());
            String type = item.get("type").asString();
            String key = jdbc.queryForObject("select answer_key from vocab_quiz_items where id = ?",
                    String.class, itemId);
            // 填空题故意填原形；选择题必须填选项键
            String given = "FILL_BLANK".equals(type) ? "struggle" : key;
            answers.add(Map.of("itemId", itemId, "given", given));
        }
        JsonNode submitted = postJson(base, cookie, "/api/vocab/quizzes/" + quizId + "/submit",
                Map.of("answers", answers));
        for (JsonNode item : submitted.get("items")) {
            if ("FILL_BLANK".equals(item.get("type").asString())) {
                String answered = item.get("answer").asString();
                boolean familyWord = "struggle".equalsIgnoreCase(answered)
                        || answered.toLowerCase().startsWith("struggl");
                if (familyWord) {
                    assertThat(item.get("correct").asBoolean()).isTrue();
                }
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private void seedContent(UUID userId, List<String> forms) {
        for (String form : forms) {
            String json = "{\"senses\":[{\"pos\":\"verb\",\"zh\":\"译\"}],"
                    + "\"examples\":[{\"en\":\"They " + form + "ed it carefully.\",\"zh\":\"例句一\"},"
                    + "{\"en\":\"We " + form + " every day.\",\"zh\":\"例句二\"}],"
                    + "\"family\":[\"" + form + "ed\",\"" + form + "ing\"]}";
            long contentId = contentStore.put("en", form, json, "TEST");
            jdbc.update("""
                    update vocab_words set content_id = ?, content_status = 'READY'
                     where user_id = ? and normalized_form = ?
                    """, contentId, userId, form);
        }
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private UUID wordId(UUID userId, String normalized) {
        return jdbc.queryForObject(
                "select id from vocab_words where user_id = ? and normalized_form = ?",
                UUID.class, userId, normalized);
    }

    private String commit(String base, String cookie, String text, String label) throws Exception {
        HttpResponse<String> response = post(base, cookie, "/api/vocab/import/commit",
                Map.of("text", text, "language", "en", "label", label));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("batchId").asString();
    }

    private UUID me(String base, String cookie) throws Exception {
        return UUID.fromString(MAPPER.readTree(send(base, cookie, "GET", "/api/auth/me", null).body())
                .get("id").asString());
    }

    private JsonNode postJson(String base, String cookie, String path, Object body) throws Exception {
        HttpResponse<String> response = post(base, cookie, path, body);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> post(String base, String cookie, String path, Object body) throws Exception {
        return send(base, cookie, "POST", path, body);
    }

    private HttpResponse<String> getRaw(String base, String cookie, String path) throws Exception {
        return send(base, cookie, "GET", path, null);
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

    private String login(String base, String email) throws Exception {
        HttpResponse<String> response = send(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(response.statusCode()).as(email + " -> " + response.body()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
