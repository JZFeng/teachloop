package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** T064：new attempt —— 同一学生只有一条 active；达到 max_attempts 后拒绝。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class NewAttemptTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("T064：交卷后可开新 attempt，最多 3 次，active 始终只有一条")
    void newAttemptsRespectMaxAttempts() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        String paperId = post(base, teacher, "/api/papers", Map.of("title", "attempt 验收卷", "defaultDurationMinutes", 60), "id");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections", Map.of("title", "单选", "pointsPerItem", 10), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "SINGLE_CHOICE", "stem", "Q1", "answerKey", "A", "points", 10,
                "paperSectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/publish", Map.of(), "id");

        String classId = createClass(base, teacher);
        join(base, student, inviteOf(base, teacher, classId));
        String offeringId = post(base, teacher, "/api/offerings", Map.of(
                "paperId", paperId, "kind", "EXAM", "dueAt", "2026-12-31T23:59:59Z",
                "durationMinutes", 60, "timingMode", "INDIVIDUAL", "maxAttempts", 3,
                "classes", List.of(Map.of("classId", classId))), "id");
        post(base, teacher, "/api/offerings/" + offeringId + "/publish", Map.of(), "id");

        submitOnce(base, student, offeringId, "A");
        Map<String, Object> attempts = send(base, student, "GET", "/api/offerings/" + offeringId + "/my-submissions", null);
        assertThat(attempts.get("canRetry")).isEqualTo(true);

        for (int attemptNo = 2; attemptNo <= 3; attemptNo++) {
            Map<String, Object> started = send(base, student, "POST",
                    "/api/offerings/" + offeringId + "/attempts", Map.of());
            assertThat(((Number) started.get("attemptNo")).intValue()).isEqualTo(attemptNo);
            submitOnce(base, student, offeringId, "A");
        }

        HttpResponse<String> fourth = raw(base, student, "POST", "/api/offerings/" + offeringId + "/attempts", Map.of());
        assertThat(fourth.statusCode()).isEqualTo(409);

        Integer activeRows = jdbc.queryForObject(
                "select count(*) from submissions where offering_id = ? and active", Integer.class,
                java.util.UUID.fromString(offeringId));
        assertThat(activeRows).isEqualTo(1);
        Integer totalRows = jdbc.queryForObject(
                "select count(*) from submissions where offering_id = ?", Integer.class,
                java.util.UUID.fromString(offeringId));
        assertThat(totalRows).isEqualTo(3);
        Map<String, Object> after = send(base, student, "GET", "/api/offerings/" + offeringId + "/my-submissions", null);
        assertThat((List<?>) after.get("attempts")).hasSize(3);
        assertThat(after.get("canRetry")).isEqualTo(false);
    }

    private void submitOnce(String base, String student, String offeringId, String answer) throws Exception {
        send(base, student, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        send(base, student, "POST", "/api/offerings/" + offeringId + "/submissions",
                Map.of("answers", List.of(Map.of("number", 1, "answer", answer))));
    }

    private String createClass(String base, String teacher) throws Exception {
        return send(base, teacher, "POST", "/api/classes", Map.of("name", "attempt 班")).get("id").toString();
    }

    private String inviteOf(String base, String teacher, String classId) throws Exception {
        HttpResponse<String> res = raw(base, teacher, "GET", "/api/classes", null);
        List<?> classes = Json.parseList(res.body());
        for (Object o : classes) {
            Map<?, ?> c = (Map<?, ?>) o;
            if (classId.equals(c.get("id"))) return c.get("inviteCode").toString();
        }
        throw new AssertionError("no class");
    }

    private void join(String base, String cookie, String invite) throws Exception {
        send(base, cookie, "POST", "/api/classes/join", Map.of("inviteCode", invite));
    }

    private String login(String base, String email) throws Exception {
        HttpResponse<String> res = raw(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(res.statusCode()).isEqualTo(200);
        return res.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
    }

    private String post(String base, String cookie, String path, Object body, String field) throws Exception {
        Object v = send(base, cookie, "POST", path, body).get(field);
        assertThat(v).isNotNull();
        return v.toString();
    }

    private Map<String, Object> send(String base, String cookie, String method, String path, Object body) throws Exception {
        HttpResponse<String> res = raw(base, cookie, method, path, body);
        assertThat(res.statusCode()).withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private HttpResponse<String> raw(String base, String cookie, String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (cookie != null) b.header("Cookie", cookie);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();
        static String write(Object o) { return MAPPER.writeValueAsString(o); }
        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) { return MAPPER.readValue(s, Map.class); }
        static List<?> parseList(String s) { return MAPPER.readValue(s, List.class); }
    }
}
