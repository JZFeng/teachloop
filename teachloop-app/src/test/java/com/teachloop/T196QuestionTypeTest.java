package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

/**
 * T196：题型模型（批量设定 / 单选↔多选互转不丢作答 / 多选 partial 计分）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class T196QuestionTypeTest {

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
    @DisplayName("T196：partial 多选按比例计分，批量设题型与互转不丢已有作答")
    void questionTypesAndPartialScoring() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        // ---------- partial 多选：选对 1/2 得一半分 ----------
        String paperId = post(base, teacher, "/api/papers",
                Map.of("title", "题型与计分验收卷", "defaultDurationMinutes", 60), "id");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "客观题", "pointsPerItem", 10), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "MULTIPLE_CHOICE", "stem", "Which two?", "options", "A. one\nB. two\nC. three",
                "answerKey", "AB", "points", 10, "paperSectionId", sectionId,
                "scoringStrategy", "PARTIAL"), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 2,
                "questionType", "SINGLE_CHOICE", "stem", "Which one?", "answerKey", "A",
                "points", 10, "paperSectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 3,
                "questionType", "FILL_BLANK", "stem", "Fill: ___", "answerKey", "alone",
                "points", 5, "paperSectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/publish", Map.of(), "id");

        String classId = createClass(base, teacher, "题型验收班");
        join(base, student, inviteOf(base, teacher, classId));
        String offeringId = post(base, teacher, "/api/offerings", Map.of(
                "paperId", paperId, "kind", "EXAM", "dueAt", "2026-12-31T23:59:59Z",
                "durationMinutes", 60, "timingMode", "INDIVIDUAL",
                "classes", List.of(Map.of("classId", classId))), "id");
        post(base, teacher, "/api/offerings/" + offeringId + "/publish", Map.of(), "id");
        // 本用例验证计分口径，先把成绩公布出去，避免决策 025 的未公布掩码挡住分数
        send(base, teacher, "POST", "/api/offerings/" + offeringId + "/scores/publish", Map.of());

        post(base, student, "/api/offerings/" + offeringId + "/start", Map.of(), "submissionId");
        Map<String, Object> submitted = send(base, student, "POST",
                "/api/offerings/" + offeringId + "/submissions",
                Map.of("answers", List.of(
                        Map.of("number", 1, "answer", "A"),      // 对 1 / 应选 2 → 5
                        Map.of("number", 2, "answer", "A"),      // 10
                        Map.of("number", 3, "answer", "alone")))); // 5
        assertThat(new BigDecimal(submitted.get("objectiveScore").toString()))
                .isEqualByComparingTo("20");

        // ---------- 批量设定题型 + 单题互转 ----------
        String draftId = post(base, teacher, "/api/papers",
                Map.of("title", "题型批量验收草稿", "defaultDurationMinutes", 60), "id");
        String draftSection = post(base, teacher, "/api/papers/" + draftId + "/sections",
                Map.of("title", "单选", "pointsPerItem", 10), "id");
        for (int n = 1; n <= 3; n++) {
            post(base, teacher, "/api/papers/" + draftId + "/items", Map.of("number", n,
                    "questionType", "SINGLE_CHOICE", "stem", "Q" + n, "answerKey",
                    String.valueOf((char) ('A' + n - 1)), "points", 10,
                    "paperSectionId", draftSection), "id");
        }
        Map<String, Object> batch = send(base, teacher, "POST",
                "/api/papers/" + draftId + "/items/question-type/batch",
                Map.of("fromNumber", 1, "toNumber", 2, "questionType", "MULTIPLE_CHOICE"));
        assertThat(((Number) batch.get("updated")).intValue()).isEqualTo(2);

        Map<String, Object> detail = get(base, teacher, "/api/papers/" + draftId);
        List<?> sections = (List<?>) detail.get("sections");
        List<?> draftItems = (List<?>) ((Map<?, ?>) sections.get(0)).get("items");
        assertThat(((Map<?, ?>) draftItems.get(0)).get("questionType").toString())
                .isEqualTo("MULTIPLE_CHOICE");
        assertThat(((Map<?, ?>) draftItems.get(1)).get("questionType").toString())
                .isEqualTo("MULTIPLE_CHOICE");
        assertThat(((Map<?, ?>) draftItems.get(2)).get("questionType").toString())
                .isEqualTo("SINGLE_CHOICE");

        send(base, teacher, "PUT", "/api/papers/" + draftId + "/items/3/question-type",
                Map.of("questionType", "MULTIPLE_CHOICE"));
        send(base, teacher, "PUT", "/api/papers/" + draftId + "/items/1/scoring-strategy",
                Map.of("scoringStrategy", "PARTIAL"));
        Map<String, Object> afterStrategy = get(base, teacher, "/api/papers/" + draftId);
        Map<?, ?> item1 = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) afterStrategy.get("sections")).get(0))
                .get("items")).get(0);
        assertThat(item1.get("scoringStrategy").toString()).isEqualTo("PARTIAL");

        // 不支持的计分策略返回 400
        HttpResponse<String> wrongType = raw(base, teacher, "PUT",
                "/api/papers/" + draftId + "/items/1/scoring-strategy",
                Map.of("scoringStrategy", "BOGUS"));
        assertThat(wrongType.statusCode()).isEqualTo(400);

        // 已发布试卷禁止改题型
        HttpResponse<String> locked = raw(base, teacher, "POST",
                "/api/papers/" + paperId + "/items/question-type/batch",
                Map.of("fromNumber", 1, "toNumber", 2, "questionType", "ESSAY"));
        assertThat(locked.statusCode()).isEqualTo(409);

        // ---------- 互转不丢已有作答：先按单选交卷，再把题目改成多选 ----------
        String convId = post(base, teacher, "/api/papers",
                Map.of("title", "互转不丢作答", "defaultDurationMinutes", 60), "id");
        String convSection = post(base, teacher, "/api/papers/" + convId + "/sections",
                Map.of("title", "单选", "pointsPerItem", 10), "id");
        post(base, teacher, "/api/papers/" + convId + "/items", Map.of("number", 1,
                "questionType", "SINGLE_CHOICE", "stem", "One answer", "answerKey", "A",
                "points", 10, "paperSectionId", convSection), "id");
        String convOffering = post(base, teacher, "/api/offerings", Map.of(
                "paperId", convId, "kind", "EXAM", "dueAt", "2026-12-31T23:59:59Z",
                "durationMinutes", 60, "timingMode", "INDIVIDUAL",
                "classes", List.of(Map.of("classId", classId))), "id");
        post(base, teacher, "/api/offerings/" + convOffering + "/publish", Map.of(), "id");
        // 本用例验证计分口径，先把成绩公布出去，避免决策 025 的未公布掩码挡住分数
        send(base, teacher, "POST", "/api/offerings/" + convOffering + "/scores/publish", Map.of());
        post(base, student, "/api/offerings/" + convOffering + "/start", Map.of(), "submissionId");
        Map<String, Object> convSubmit = send(base, student, "POST",
                "/api/offerings/" + convOffering + "/submissions",
                Map.of("answers", List.of(Map.of("number", 1, "answer", "A"))));
        assertThat(new BigDecimal(convSubmit.get("objectiveScore").toString()))
                .isEqualByComparingTo("10");

        send(base, teacher, "PUT", "/api/papers/" + convId + "/items/1/question-type",
                Map.of("questionType", "MULTIPLE_CHOICE"));

        Integer answerRows = jdbc.queryForObject(
                "select count(*) from objective_answers oa join submissions s on s.id = oa.submission_id "
                        + "where s.offering_id = ?", Integer.class,
                java.util.UUID.fromString(convOffering));
        assertThat(answerRows).isEqualTo(1);
        Map<String, Object> mine = get(base, student,
                "/api/offerings/" + convOffering + "/my-submissions");
        Map<?, ?> attempt = (Map<?, ?>) ((List<?>) mine.get("attempts")).get(0);
        assertThat(new BigDecimal(attempt.get("objectiveScore").toString()))
                .isEqualByComparingTo("10");
        Map<String, Object> convDetail = get(base, teacher, "/api/papers/" + convId);
        Map<?, ?> convItem = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) convDetail.get("sections")).get(0))
                .get("items")).get(0);
        assertThat(convItem.get("questionType").toString()).isEqualTo("MULTIPLE_CHOICE");
    }

    // ---------- helpers ----------

    private String createClass(String base, String teacher, String name) throws Exception {
        return send(base, teacher, "POST", "/api/classes", Map.of("name", name)).get("id").toString();
    }

    private String inviteOf(String base, String teacher, String classId) throws Exception {
        HttpResponse<String> res = raw(base, teacher, "GET", "/api/classes", null);
        assertThat(res.statusCode()).isEqualTo(200);
        List<?> classes = Json.parseList(res.body());
        for (Object o : classes) {
            Map<?, ?> c = (Map<?, ?>) o;
            if (classId.equals(c.get("id"))) {
                return c.get("inviteCode").toString();
            }
        }
        throw new AssertionError("找不到班级 " + classId);
    }

    private void join(String base, String cookie, String invite) throws Exception {
        send(base, cookie, "POST", "/api/classes/join", Map.of("inviteCode", invite));
    }

    private String login(String base, String email) throws Exception {
        HttpResponse<String> res = raw(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(res.statusCode()).as("login %s: %s", email, res.body()).isEqualTo(200);
        List<String> cookies = res.headers().allValues("set-cookie");
        return cookies.stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no cookie"));
    }

    private Map<String, Object> get(String base, String cookie, String path) throws Exception {
        return send(base, cookie, "GET", path, null);
    }

    private String post(String base, String cookie, String path, Object body, String field) throws Exception {
        Object v = send(base, cookie, "POST", path, body).get(field);
        assertThat(v).as("%s missing %s", path, field).isNotNull();
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpResponse<String> res = raw(base, cookie, method, path, body);
        assertThat(res.statusCode()).withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private HttpResponse<String> raw(String base, String cookie, String method, String path, Object body)
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

        static String write(Object o) { return MAPPER.writeValueAsString(o); }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) { return MAPPER.readValue(s, Map.class); }

        static List<?> parseList(String s) { return MAPPER.readValue(s, List.class); }
    }
}
