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
        r.add("teachloop.grading.dispatch-ms", () -> "3600000"); // 关掉自动派发，测试里手动派发
        r.add("teachloop.grading.sweep-ms", () -> "3600000"); // 关掉定时清扫，避免干扰手动流程
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
    @DisplayName("布置 create 带 publish=true 必须真的落成 PUBLISHED（2026-10-20 实测缺陷回归）")
    void publishingOnCreateActuallyPersists() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        Map<String, Object> cls = send(base, teacher, "POST", "/api/classes", Map.of("name", "写作（2）班"));
        String classId = cls.get("id").toString();
        String invite = cls.get("inviteCode").toString();

        // 关键：create 时直接 publish，不再单独调 /publish。
        // 作业自带内容（D-14）：不带 paperId，内容全部来自请求体。
        String offeringId = post(base, teacher, "/api/offerings", Map.of(
                "title", "写作作业（发布即生效）",
                "instructions", "Write a speech about your club.",
                "totalPoints", 15,
                "rubricMarkdown", "# 评分标准\n- 内容要点\n- 语言准确性",
                "dueAt", "2026-12-31T23:59:59Z",
                "timingMode", "INDIVIDUAL",
                "classes", List.of(Map.of("classId", classId)),
                "publish", true), "id");

        post(base, student, "/api/classes/join", Map.of("inviteCode", invite), "name");
        // 学生的 entry 只对「已发布 + 参与班」开放：库里若还是 DRAFT，这里直接 404
        Map<String, Object> entry = get(base, student, "/api/offerings/" + offeringId + "/entry");
        assertThat(entry).as("学生能打开刚布置的作业，说明库里确实是 PUBLISHED").containsKey("dueAt");
    }

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

        // T092：交卷只进 QUEUED；派发（手动、不受峰谷限制）才把这一场放进队列。
        Submission queued = submissions.findById(UUID.fromString(submissionId)).orElseThrow();
        assertThat(queued.getGradingState()).isEqualTo(GradingState.QUEUED);

        Map<String, Object> dispatch = send(base, teacher, "POST",
                "/api/offerings/" + offeringId + "/grading/dispatch", Map.of());
        assertThat(dispatch.get("runId")).isNotNull();
        assertThat(dispatch.get("firstDispatch")).isEqualTo(true);
        assertThat(((Number) dispatch.get("enqueued")).intValue()).isEqualTo(1);

        worker.processBatch(10);

        Submission s = submissions.findById(UUID.fromString(submissionId)).orElseThrow();
        assertThat(s.getGradingState()).isEqualTo(GradingState.DONE);
        assertThat(s.getObjectiveTotal()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(s.getSubjectiveTotal()).isPositive();
        assertThat(reviews.findBySubmissionId(s.getId()).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.PENDING);
        assertThat(auditLogs.count()).isGreaterThanOrEqualTo(4);

        // T094：一条聚合 SQL；终态下 done = 1
        Map<String, Object> progress = get(base, teacher, "/api/offerings/" + offeringId + "/progress");
        assertThat(((Number) progress.get("total")).intValue()).isEqualTo(1);
        assertThat(((Number) progress.get("done")).longValue()).isEqualTo(1);
        assertThat(((Number) progress.get("pending")).longValue()).isZero();
    }

    @Test
    @DisplayName("两道写作题：各自 rubric 进快照，逐篇判分合并，复核逐项带题号（D-24 / D-26）")
    void twoEssaysAreGradedPerItem() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        String paperId = post(base, teacher, "/api/papers",
                Map.of("title", "写作双题卷", "defaultDurationMinutes", 60), "id");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "第四部分 写作", "pointsPerItem", 15), "id");
        String rubric15 = "# 评分标准\n- 表达理解（5 分）：是否回应朋友的处境。\n- 给出建议（10 分）：建议是否具体。";
        String rubric16 = "# 评分标准\n- 社团简介（5 分）：是否点明社团。\n- 社团活动（10 分）：活动是否具体。";
        String sample15 = "Dear Chen Ming, I understand your feeling...";
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 15,
                "questionType", "ESSAY", "stem", "写一封回信。", "points", 15, "sectionId", sectionId,
                "rubricMarkdown", rubric15, "referenceMarkdown", sample15), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 16,
                "questionType", "ESSAY", "stem", "写一篇招新演讲稿。", "points", 15, "sectionId", sectionId,
                "rubricMarkdown", rubric16), "id");

        Map<String, Object> detail = get(base, teacher, "/api/papers/" + paperId);
        assertThat(((Number) detail.get("essayContentPercent")).intValue())
                .as("D-28：向导能看到当前作文默认内容占比").isEqualTo(80);
        List<?> sections = (List<?>) detail.get("sections");
        List<?> items = (List<?>) ((Map<?, ?>) sections.get(0)).get("items");
        assertThat(items).hasSize(2);
        assertThat(String.valueOf(((Map<?, ?>) items.get(0)).get("rubricMarkdown"))).contains("表达理解");
        assertThat(String.valueOf(((Map<?, ?>) items.get(0)).get("referenceMarkdown")))
                .isEqualTo(sample15);

        post(base, teacher, "/api/papers/" + paperId + "/publish", Map.of(), "id");
        Map<String, Object> cls = send(base, teacher, "POST", "/api/classes", Map.of("name", "写作双题班"));
        String classId = cls.get("id").toString();
        String invite = cls.get("inviteCode").toString();
        String offeringId = post(base, teacher, "/api/offerings", Map.of(
                "paperId", paperId, "kind", "EXAM", "dueAt", "2026-12-31T23:59:59Z",
                "durationMinutes", 60, "timingMode", "INDIVIDUAL",
                "classIds", List.of(classId)), "id");
        post(base, teacher, "/api/offerings/" + offeringId + "/publish", Map.of(), "id");
        post(base, student, "/api/classes/join", Map.of("inviteCode", invite), "name");
        post(base, student, "/api/offerings/" + offeringId + "/start", Map.of(), "submissionId");
        String submissionId = post(base, student, "/api/offerings/" + offeringId + "/submissions",
                Map.of("answers", List.of(
                        Map.of("number", 15, "answer", "Dear Chen Ming, I understand your feeling."),
                        Map.of("number", 16, "answer", "Good morning, everyone! Our club meets weekly."))),
                "submissionId");

        send(base, teacher, "POST", "/api/offerings/" + offeringId + "/grading/dispatch", Map.of());
        worker.processBatch(10);

        Submission s = submissions.findById(UUID.fromString(submissionId)).orElseThrow();
        assertThat(s.getGradingState()).isEqualTo(GradingState.DONE);
        // stub 0.75：15 分两篇 → 11.3 + 11.3
        assertThat(s.getSubjectiveTotal()).isEqualByComparingTo("22.6");
        String criteria = reviews.findBySubmissionId(s.getId()).orElseThrow().getAiCriteria();
        assertThat(criteria).contains("\"itemNumber\":15").contains("\"itemNumber\":16");
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
