package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.grading.domain.ReviewStatus;
import com.teachloop.grading.service.GradingWorker;
import com.teachloop.observability.domain.AuditLogRepository;
import com.teachloop.sitting.domain.GradingState;
import com.teachloop.sitting.domain.Submission;
import com.teachloop.sitting.domain.SubmissionRepository;
import java.math.BigDecimal;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 一条真实链路：建卷 → 发布 → 建班 → 布置给班 → 发布 → 学生加入 → 开始 → 提交
 * → 客观题当场判分 → worker 判主观 → 复核队列 → 审计留痕。
 *
 * <p>用 JDK HttpClient 而不是 TestRestTemplate：Boot 4 的 starter-test 不再带 web client。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FlowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.grading.poll-ms", () -> "3600000"); // 关掉后台轮询，测试里手动跑一批
    }

    @Autowired
    Environment env;

    @Autowired
    SubmissionRepository submissions;

    @Autowired
    ReviewRepository reviews;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    GradingWorker worker;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("作业全链路：客观题当场出分，主观题由 worker 判，落复核队列并写审计")
    void endToEnd() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        String paperId = post(base, teacher, "/api/papers",
                Map.of("title", "第一次月考", "defaultDurationMinutes", 90), "id");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "听力 第一节", "pointsPerItem", 1.5), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "SINGLE_CHOICE", "stem", "What film?", "answerKey", "A",
                "points", 1.5, "paperSectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 2,
                "questionType", "SINGLE_CHOICE", "stem", "What does she offer?", "answerKey", "B",
                "points", 1.5, "paperSectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 3,
                "questionType", "ESSAY", "stem", "Write about your class.", "points", 15,
                "paperSectionId", sectionId), "id");
        send(base, teacher, "PUT", "/api/papers/" + paperId + "/rubric",
                Map.of("rubricMarkdown", "# 评分标准\n- 内容要点\n- 语言准确性"));
        post(base, teacher, "/api/papers/" + paperId + "/publish", Map.of(), "id");

        Map<String, Object> cls = send(base, teacher, "POST", "/api/classes", Map.of("name", "高一（3）班"));
        String classId = cls.get("id").toString();
        String invite = cls.get("inviteCode").toString();

        String offeringId = post(base, teacher, "/api/offerings", Map.of(
                "paperId", paperId, "kind", "EXAM", "dueAt", "2026-12-31T23:59:59Z",
                "durationMinutes", 90, "timingMode", "INDIVIDUAL",
                "classIds", List.of(classId)), "id");
        post(base, teacher, "/api/offerings/" + offeringId + "/publish", Map.of(), "id");

        post(base, student, "/api/classes/join", Map.of("inviteCode", invite), "name");
        post(base, student, "/api/offerings/" + offeringId + "/start", Map.of(), "submissionId");
        String submissionId = post(base, student, "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", "I like my English class. My teacher is patient.",
                        "answers", List.of(Map.of("number", 1, "answer", "A"),
                                Map.of("number", 2, "answer", "C"))),
                "submissionId");

        worker.processBatch(10);

        Submission s = submissions.findById(UUID.fromString(submissionId)).orElseThrow();
        assertThat(s.getGradingState()).isEqualTo(GradingState.DONE);
        assertThat(s.getObjectiveTotal()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(s.getSubjectiveTotal()).isPositive();
        assertThat(reviews.findBySubmissionId(s.getId()).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.PENDING);
        assertThat(auditLogs.count()).isGreaterThanOrEqualTo(4);
    }

    // ---------- helpers ----------

    private String login(String base, String email) throws Exception {
        HttpResponse<String> res = request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(res.statusCode()).as("login %s: %s", email, res.body()).isEqualTo(200);
        // 登录时 Spring Security 会换 session id，可能返回多个 Set-Cookie：取最后一个 SESSION
        List<String> cookies = res.headers().allValues("set-cookie");
        return cookies.stream()
                .map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("登录没有下发会话 cookie：" + cookies));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpResponse<String> res = request(base, cookie, method, path, body);
        assertThat(res.statusCode()).withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private Map<String, Object> get(String base, String cookie, String path) throws Exception {
        return send(base, cookie, "GET", path, null);
    }

    private String post(String base, String cookie, String path, Object body, String field) throws Exception {
        Object v = send(base, cookie, "POST", path, body).get(field);
        assertThat(v).as("%s 返回缺少字段 %s", path, field).isNotNull();
        return v.toString();
    }

    private HttpResponse<String> request(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 极简 JSON 读写，避免依赖 Jackson 版本细节。 */
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
