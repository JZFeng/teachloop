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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T054：发布后改结构必须 confirm，并写审计；发布递增 rubric_version。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaperPublishLockTest {

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
    @DisplayName("T054：发布后编辑 409；confirm=true 放行并审计；rubricVersion 递增")
    void publishLocksStructure() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base);
        String paperId = post(base, teacher, "/api/papers", Map.of("title", "发布锁验收卷", "defaultDurationMinutes", 60), "id");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "单选", "pointsPerItem", 10), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1, "questionType", "SINGLE_CHOICE",
                "stem", "Q1", "answerKey", "A", "points", 10, "paperSectionId", sectionId), "id");

        Map<String, Object> published = Json.parse(request(base, teacher, "POST",
                "/api/papers/" + paperId + "/publish", Map.of()).body());
        int rubricVersion = ((Number) published.get("rubricVersion")).intValue();
        assertThat(rubricVersion).isGreaterThanOrEqualTo(1);

        HttpResponse<String> blocked = request(base, teacher, "POST", "/api/papers/" + paperId + "/items",
                Map.of("number", 2, "questionType", "FILL_BLANK", "stem", "Q2", "answerKey", "x",
                        "points", 5, "paperSectionId", sectionId));
        assertThat(blocked.statusCode()).isEqualTo(409);

        HttpResponse<String> confirmed = request(base, teacher, "POST",
                "/api/papers/" + paperId + "/items?confirm=true",
                Map.of("number", 2, "questionType", "FILL_BLANK", "stem", "Q2", "answerKey", "x",
                        "points", 5, "paperSectionId", sectionId));
        assertThat(confirmed.statusCode()).isEqualTo(200);
        Integer audits = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'paper:edited_after_publish' and target_id = ?",
                Integer.class, paperId);
        assertThat(audits).isEqualTo(1);

        Map<String, Object> republished = Json.parse(request(base, teacher, "POST",
                "/api/papers/" + paperId + "/publish", Map.of()).body());
        assertThat(((Number) republished.get("rubricVersion")).intValue()).isGreaterThan(rubricVersion);
    }

    private String login(String base) throws Exception {
        HttpResponse<String> res = request(base, null, "POST", "/api/auth/login",
                Map.of("email", "teacher@teachloop.local", "password", "teachloop123"));
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
    }
}
