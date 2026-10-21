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

/**
 * 试卷结构 CRUD：部分的改与删、题项的删。
 *
 * <p>补的是向导第 2/3 步「建错了只能新建整卷」的缺口：改标题与每题分值、删部分（连带题项与评分项）、
 * 删题项并重算总分；已发布试卷仍走 confirm 闸门。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaperCrudTest {

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
    @DisplayName("改部分标题与每题分值；不影响已录入题项的分值")
    void updateSectionKeepsItemPoints() throws Exception {
        String base = base();
        String teacher = login(base);
        String paperId = createPaper(base, teacher, "改部分验收卷");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "阅读理解", "pointsPerItem", 5), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "SINGLE_CHOICE", "stem", "Q1", "answerKey", "A", "points", 5,
                "sectionId", sectionId), "id");

        HttpResponse<String> updated = request(base, teacher, "PUT",
                "/api/papers/" + paperId + "/sections/" + sectionId,
                Map.of("title", "阅读", "pointsPerItem", 8));
        assertThat(updated.statusCode()).isEqualTo(200);
        Map<String, Object> section = Json.parse(updated.body());
        assertThat(section.get("title")).isEqualTo("阅读");
        assertThat(((Number) section.get("pointsPerItem")).intValue()).isEqualTo(8);
        List<Map<String, Object>> items = itemsOf(section);
        assertThat(items).hasSize(1);
        assertThat(((Number) items.get(0).get("points")).intValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("删部分连带题项与评分项，总分重算；之后新增的部分位置不撞车")
    void deleteSectionCascadesAndRepositions() throws Exception {
        String base = base();
        String teacher = login(base);
        String paperId = createPaper(base, teacher, "删部分验收卷");
        String first = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "听力", "pointsPerItem", 2), "id");
        String second = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "阅读", "pointsPerItem", 5), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "SINGLE_CHOICE", "stem", "听力 1", "answerKey", "A", "points", 2,
                "sectionId", first), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 2,
                "questionType", "FILL_BLANK", "stem", "阅读 1", "answerKey", "word", "points", 5,
                "sectionId", second), "id");

        HttpResponse<String> deleted = request(base, teacher, "DELETE",
                "/api/papers/" + paperId + "/sections/" + first, null);
        assertThat(deleted.statusCode()).isEqualTo(204);

        Map<String, Object> detail = Json.parse(request(base, teacher, "GET",
                "/api/papers/" + paperId, null).body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) detail.get("sections");
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).get("title")).isEqualTo("阅读");
        assertThat(itemsOf(sections.get(0))).hasSize(1);
        assertThat(((Number) ((Map<String, Object>) detail.get("paper")).get("totalPoints")).intValue())
                .isEqualTo(5);

        String third = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "写作", "pointsPerItem", 15), "id");
        Map<String, Object> newSection = Json.parse(request(base, teacher, "PUT",
                "/api/papers/" + paperId + "/sections/" + third,
                Map.of("title", "写作", "pointsPerItem", 15)).body());
        assertThat(((Number) newSection.get("position")).intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("删题项后总分重算，题号可再次使用")
    void deleteItemRecalculatesTotal() throws Exception {
        String base = base();
        String teacher = login(base);
        String paperId = createPaper(base, teacher, "删题项验收卷");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "单选", "pointsPerItem", 10), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "SINGLE_CHOICE", "stem", "Q1", "answerKey", "A", "points", 10,
                "sectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 2,
                "questionType", "SINGLE_CHOICE", "stem", "Q2", "answerKey", "B", "points", 10,
                "sectionId", sectionId), "id");

        HttpResponse<String> deleted = request(base, teacher, "DELETE",
                "/api/papers/" + paperId + "/items/1", null);
        assertThat(deleted.statusCode()).isEqualTo(204);
        Map<String, Object> detail = Json.parse(request(base, teacher, "GET",
                "/api/papers/" + paperId, null).body());
        assertThat(((Number) ((Map<String, Object>) detail.get("paper")).get("totalPoints")).intValue())
                .isEqualTo(10);

        // 题号 1 已释放，可被重新录入
        HttpResponse<String> readded = request(base, teacher, "POST",
                "/api/papers/" + paperId + "/items", Map.of("number", 1,
                        "questionType", "FILL_BLANK", "stem", "Q1 新", "answerKey", "x", "points", 3,
                        "sectionId", sectionId));
        assertThat(readded.statusCode()).isEqualTo(200);
        Map<String, Object> again = Json.parse(request(base, teacher, "GET",
                "/api/papers/" + paperId, null).body());
        assertThat(((Number) ((Map<String, Object>) again.get("paper")).get("totalPoints")).intValue())
                .isEqualTo(13);
    }

    @Test
    @DisplayName("已发布试卷：删题项/删部分 409，confirm=true 放行并写审计")
    void publishedPaperRequiresConfirm() throws Exception {
        String base = base();
        String teacher = login(base);
        String paperId = createPaper(base, teacher, "发布后删题验收卷");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "单选", "pointsPerItem", 10), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "SINGLE_CHOICE", "stem", "Q1", "answerKey", "A", "points", 10,
                "sectionId", sectionId), "id");
        assertThat(request(base, teacher, "POST", "/api/papers/" + paperId + "/publish", Map.of())
                .statusCode()).isEqualTo(200);

        assertThat(request(base, teacher, "DELETE", "/api/papers/" + paperId + "/items/1", null)
                .statusCode()).isEqualTo(409);
        assertThat(request(base, teacher, "DELETE",
                "/api/papers/" + paperId + "/items/1?confirm=true", null).statusCode()).isEqualTo(204);

        Integer publishedEdits = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'paper:edited_after_publish' and target_id = ?",
                Integer.class, paperId);
        assertThat(publishedEdits).isEqualTo(1);
        Integer deletions = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'paper:item_deleted' and target_id = ?",
                Integer.class, paperId);
        assertThat(deletions).isEqualTo(1);
    }

    @Test
    @DisplayName("别的试卷的部分 ID 不能拿来改（越界 404）")
    void sectionMustBelongToPaper() throws Exception {
        String base = base();
        String teacher = login(base);
        String paperA = createPaper(base, teacher, "越界 A 卷");
        String paperB = createPaper(base, teacher, "越界 B 卷");
        String sectionOfB = post(base, teacher, "/api/papers/" + paperB + "/sections",
                Map.of("title", "B 的部分", "pointsPerItem", 1), "id");

        HttpResponse<String> res = request(base, teacher, "PUT",
                "/api/papers/" + paperA + "/sections/" + sectionOfB,
                Map.of("title", "偷改", "pointsPerItem", 2));
        assertThat(res.statusCode()).isEqualTo(404);
    }

    // ---- helpers ----

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private String createPaper(String base, String teacher, String title) throws Exception {
        return post(base, teacher, "/api/papers",
                Map.of("title", title, "defaultDurationMinutes", 60), "id");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> section) {
        return (List<Map<String, Object>>) section.get("items");
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
        HttpResponse<String> res = request(base, cookie, "POST", path, body);
        assertThat(res.statusCode()).as(path + " -> " + res.body()).isEqualTo(200);
        return Json.parse(res.body()).get(field).toString();
    }

    private HttpResponse<String> request(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (cookie != null) b.header("Cookie", cookie);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static String write(Object o) {
            return MAPPER.writeValueAsString(o);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) {
            return MAPPER.readValue(s, Map.class);
        }
    }
}
