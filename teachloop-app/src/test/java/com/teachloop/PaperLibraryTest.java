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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T206：布置次数、复制互不影响、归档。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaperLibraryTest {

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

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("T206：布置 3 次显示 3；复制件独立；归档状态")
    void libraryStatsCopyArchive() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base);
        String paperId = post(base, teacher, "/api/papers", Map.of("title", "库存卷"), "id");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "单选", "pointsPerItem", 10), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "SINGLE_CHOICE", "stem", "原题干", "answerKey", "A", "points", 10,
                "paperSectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/publish", Map.of(), "id");

        String classId = post(base, teacher, "/api/classes", Map.of("name", "库存班"), "id");
        for (int i = 0; i < 3; i++) {
            post(base, teacher, "/api/offerings", Map.of("paperId", paperId, "kind", "EXAM",
                    "timingMode", "INDIVIDUAL", "dueAt", "2026-12-31T23:59:59Z", "durationMinutes", 60,
                    "classes", List.of(Map.of("classId", classId))), "id");
        }
        List<?> papers = Json.parseList(request(base, teacher, "GET", "/api/papers", null).body());
        Map<?, ?> source = papers.stream().map(o -> (Map<?, ?>) o)
                .filter(m -> paperId.equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(((Number) source.get("assignmentCount")).intValue()).isEqualTo(3);

        Map<String, Object> copy = Json.parse(request(base, teacher, "POST",
                "/api/papers/" + paperId + "/copy", Map.of()).body());
        assertThat(copy.get("id")).isNotEqualTo(paperId);
        assertThat(copy.get("status")).isEqualTo("DRAFT");
        assertThat(copy.get("title").toString()).contains("副本");
        String copyId = copy.get("id").toString();

        request(base, teacher, "PUT", "/api/papers/" + copyId + "/items/1",
                Map.of("stem", "副本新题干"));
        Map<String, Object> original = Json.parse(request(base, teacher, "GET", "/api/papers/" + paperId, null).body());
        List<?> sections = (List<?>) original.get("sections");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) ((Map<?, ?>) sections.get(0)).get("items")).get(0);
        assertThat(first.get("stem")).isEqualTo("原题干");

        Map<String, Object> archived = Json.parse(request(base, teacher, "POST",
                "/api/papers/" + paperId + "/archive", Map.of()).body());
        assertThat(archived.get("status")).isEqualTo("ARCHIVED");
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

        static List<?> parseList(String s) { return MAPPER.readValue(s, List.class); }
    }
}
