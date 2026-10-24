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

/** T408 验收：发词快照、收件人快照、非本人班级 404、学生 403、领取幂等与来源追加。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false"
        })
@Testcontainers
class VocabAssignmentTest {

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
    @DisplayName("发词→学生待办→领取快照→重复领取幂等→改词集不影响已发；越权 404/403")
    void assignAndClaim() throws Exception {
        String base = base();
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        JsonNode cls = post(base, teacher, "/api/classes", Map.of("name", "Unit 5 班"));
        String classId = cls.get("id").asString();
        post(base, student, "/api/classes/join", Map.of("inviteCode", cls.get("inviteCode").asString()));

        JsonNode set = post(base, teacher, "/api/vocab/sets",
                Map.of("name", "发词测试词集", "language", "en"));
        String setId = set.get("id").asString();
        post(base, teacher, "/api/vocab/sets/" + setId + "/words",
                Map.of("words", List.of("alpha", "beta")));

        JsonNode created = post(base, teacher, "/api/vocab/assignments", Map.of(
                "setId", setId, "classIds", List.of(classId), "note", "预习"));
        String assignmentId = created.get("id").asString();
        assertThat(created.get("wordCount").asInt()).isEqualTo(2);
        assertThat(created.get("recipientCount").asInt()).isEqualTo(1);

        JsonNode detail = get(base, student, "/api/vocab/assignments/" + assignmentId);
        assertThat(detail.get("words")).hasSize(2);
        assertThat(detail.get("claimed").asBoolean()).isFalse();
        assertThat(detail.get("expired").asBoolean()).isFalse();

        JsonNode pending = get(base, student, "/api/vocab/assignments/pending");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("setName").asString()).isEqualTo("发词测试词集");

        JsonNode recordsBefore = get(base, teacher, "/api/vocab/assignments");
        assertThat(recordsBefore).hasSize(1);
        assertThat(recordsBefore.get(0).get("setName").asString()).isEqualTo("发词测试词集");
        assertThat(recordsBefore.get(0).get("recipientCount").asInt()).isEqualTo(1);
        assertThat(recordsBefore.get(0).get("claimedCount").asInt()).isZero();

        // 发送后改词集：不影响已发布置的快照
        post(base, teacher, "/api/vocab/sets/" + setId + "/words", Map.of("words", List.of("gamma")));
        JsonNode claim = post(base, student, "/api/vocab/assignments/" + assignmentId + "/claim", Map.of());
        assertThat(claim.get("added").asInt()).isEqualTo(2);
        assertThat(claim.get("duplicated").asInt()).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_assignment_words where assignment_id = ?",
                Integer.class, UUID.fromString(assignmentId))).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_words where normalized_form in ('alpha','beta')",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_word_sources where kind = 'ASSIGNMENT'", Integer.class))
                .isEqualTo(2);

        assertThat(get(base, student, "/api/vocab/assignments/" + assignmentId)
                .get("claimed").asBoolean()).isTrue();

        JsonNode again = post(base, student, "/api/vocab/assignments/" + assignmentId + "/claim", Map.of());
        assertThat(again.get("added").asInt()).isZero();
        assertThat(again.get("duplicated").asInt()).isEqualTo(2);

        assertThat(get(base, teacher, "/api/vocab/assignments").get(0).get("claimedCount").asInt())
                .isEqualTo(1);

        // 非本人班级 404；学生发词 403
        HttpResponse<String> notOwned = postRaw(base, teacher, "/api/vocab/assignments", Map.of(
                "setId", setId, "classIds", List.of(UUID.randomUUID())));
        assertThat(notOwned.statusCode()).isEqualTo(404);
        HttpResponse<String> studentAssign = postRaw(base, student, "/api/vocab/assignments", Map.of(
                "setId", setId, "classIds", List.of(classId)));
        assertThat(studentAssign.statusCode()).isEqualTo(403);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
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
