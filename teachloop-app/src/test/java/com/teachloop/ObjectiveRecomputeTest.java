package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** T102：改标准答案后，已交卷的客观分被重算并写审计。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ObjectiveRecomputeTest {

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
    @DisplayName("T102：答案 A→B 后，原先答 A 的提交客观分从 10 变 0，并写重算审计")
    void recomputesObjectiveAfterAnswerKeyChange() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");
        String paperId = post(base, teacher, "/api/papers", Map.of("title", "重算卷"), "id");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "单选", "pointsPerItem", 10), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "SINGLE_CHOICE", "stem", "Q1", "answerKey", "A", "points", 10,
                "paperSectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/publish", Map.of(), "id");
        String classId = post(base, teacher, "/api/classes", Map.of("name", "重算班"), "id");
        List<?> classes = Json.parseList(request(base, teacher, "GET", "/api/classes", null).body());
        String invite = ((Map<?, ?>) classes.get(0)).get("inviteCode").toString();
        request(base, student, "POST", "/api/classes/join", Map.of("inviteCode", invite));
        String offeringId = post(base, teacher, "/api/offerings", Map.of("paperId", paperId, "kind", "EXAM",
                "timingMode", "INDIVIDUAL", "dueAt", "2026-12-31T23:59:59Z", "durationMinutes", 60,
                "classes", List.of(Map.of("classId", classId))), "id");
        post(base, teacher, "/api/offerings/" + offeringId + "/publish", Map.of(), "id");
        request(base, student, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        request(base, student, "POST", "/api/offerings/" + offeringId + "/submissions",
                Map.of("answers", List.of(Map.of("number", 1, "answer", "A"))));

        Map<String, Object> before = Json.parse(request(base, student, "GET",
                "/api/offerings/" + offeringId + "/my-submissions", null).body());
        assertThat(decimal((Map<?, ?>) ((List<?>) before.get("attempts")).get(0), "objectiveScore"))
                .isEqualByComparingTo("10.0");

        HttpResponse<String> edit = request(base, teacher, "PUT", "/api/papers/" + paperId + "/items/1?confirm=true",
                Map.of("answerKey", "B"));
        assertThat(edit.statusCode()).isEqualTo(200);

        Map<String, Object> after = Json.parse(request(base, student, "GET",
                "/api/offerings/" + offeringId + "/my-submissions", null).body());
        assertThat(decimal((Map<?, ?>) ((List<?>) after.get("attempts")).get(0), "objectiveScore"))
                .isEqualByComparingTo("0");
        Integer audits = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'offering:objective_recomputed' and target_id = ?",
                Integer.class, offeringId);
        assertThat(audits).isGreaterThanOrEqualTo(1);
    }

    private static java.math.BigDecimal decimal(Map<?, ?> row, String field) {
        return new java.math.BigDecimal(String.valueOf(row.get(field)));
    }

    private String login(String base, String email) throws Exception {
        HttpResponse<String> res = request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(res.statusCode()).isEqualTo(200);
        return res.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
    }

    private String post(String base, String cookie, String path, Object body, String field) throws Exception {
        Map<String, Object> parsed = Json.parse(request(base, cookie, "POST", path, body).body());
        return parsed.get(field).toString();
    }

    private HttpResponse<String> request(String base, String cookie, String method, String path, Object body)
            throws Exception {
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
