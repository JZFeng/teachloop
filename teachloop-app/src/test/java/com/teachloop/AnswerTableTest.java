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

/** T055/T056：答案表补齐 + 单题修正。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnswerTableTest {

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
    @DisplayName("T055/T056：1-5 ABCDA 补齐答案；错误行号 400；单题可改")
    void answerTableAndItemEdit() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base);
        String paperId = post(base, teacher, "/api/papers", Map.of("title", "答案表验收卷"), "id");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "单选", "pointsPerItem", 5), "id");
        for (int n = 1; n <= 5; n++) {
            post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", n,
                    "questionType", "SINGLE_CHOICE", "stem", "Q" + n, "points", 5,
                    "paperSectionId", sectionId), "id");
        }

        Map<String, Object> filled = Json.parse(request(base, teacher, "POST",
                "/api/papers/" + paperId + "/answers", Map.of("text", "1-5 ABCDA")).body());
        assertThat(((Number) filled.get("updated")).intValue()).isEqualTo(5);

        Map<String, Object> detail = Json.parse(request(base, teacher, "GET", "/api/papers/" + paperId, null).body());
        // 通过 detail 的 sections/items 校验答案
        List<?> sections = (List<?>) detail.get("sections");
        List<?> sectionItems = (List<?>) ((Map<?, ?>) sections.get(0)).get("items");
        List<String> keys = sectionItems.stream()
                .map(o -> String.valueOf(((Map<?, ?>) o).get("answerKey"))).toList();
        assertThat(keys).containsExactly("A", "B", "C", "D", "A");

        HttpResponse<String> bad = request(base, teacher, "POST", "/api/papers/" + paperId + "/answers",
                Map.of("text", "not-an-answer"));
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("第 1 行");

        Map<String, Object> edited = Json.parse(request(base, teacher, "PUT",
                "/api/papers/" + paperId + "/items/1",
                Map.of("stem", "修正后的题干", "answerKey", "C", "points", 7)).body());
        assertThat(edited.get("answerKey")).isEqualTo("C");
        assertThat(edited.get("stem")).isEqualTo("修正后的题干");
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
