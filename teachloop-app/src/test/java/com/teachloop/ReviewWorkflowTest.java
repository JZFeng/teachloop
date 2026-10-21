package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.grading.domain.Review;
import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.grading.domain.ReviewStatus;
import com.teachloop.grading.service.GradingWorker;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.observability.domain.AuditLog;
import com.teachloop.observability.domain.AuditLogRepository;
import com.teachloop.sitting.domain.Submission;
import com.teachloop.sitting.domain.SubmissionRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T101–T104 验收：复核编辑、发布、撤回、批量通过。
 *
 * <p>覆盖：编辑只改主观分并经端口回写 submission 总分、审计含 AI 草稿→最终分与文本 diff、
 * 未批准发布 409、发布后编辑 409、撤回后可再次发布、批量通过带 skipped、学生 403。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReviewWorkflowTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // 批改只在测试里手动跑 worker，关掉后台定时，避免与断言抢时序
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
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

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    private final HttpClient client = HttpClient.newHttpClient();

    private String base;
    private String teacher;

    @BeforeEach
    void setUp() throws Exception {
        base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        teacher = login("teacher@teachloop.local");
    }

    @Test
    @DisplayName("T101/T103：编辑回写总分与审计 diff；未批准不可发布；发布后不可编辑；可撤回")
    void editPublishWithdrawFlow() throws Exception {
        Fixture f = fixture();
        Map<String, Object> submitted = submit(f.studentA(), f.offeringId(),
                answers(1, "A", 2, "C"), "我的作文内容。");
        UUID submissionId = UUID.fromString(submitted.get("submissionId").toString());
        dispatchAndGrade(f.offeringId());
        String path = "/api/submissions/" + submissionId + "/review";

        Review review = reviews.findBySubmissionId(submissionId).orElseThrow();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(review.getOriginalSubjective()).as("批改完成时捕获 AI 主观草稿")
                .isEqualByComparingTo("7.5");

        // ① 复核详情：正文、题项与作答、AI 主观草稿、复核字段都在
        Map<String, Object> detail = get(teacher, path);
        assertThat(detail.get("contentText")).isEqualTo("我的作文内容。");
        assertThat((List<?>) detail.get("answers")).hasSize(2);
        assertThat((List<?>) detail.get("items")).hasSize(3);
        assertThat(new BigDecimal(detail.get("aiSubjectiveDraft").toString()))
                .isEqualByComparingTo("7.5");
        assertThat(((Map<?, ?>) detail.get("review")).get("status")).isEqualTo("PENDING");

        // ② 未批准发布 → 409
        assertThat(raw(teacher, "POST", path + "/publish", Map.of()).statusCode()).isEqualTo(409);

        // ③ 编辑：改主观分 + 文本；首次编辑写 original_subjective，不动客观分
        assertThat(raw(teacher, "PUT", path, Map.of(
                "subjectivePoints", new BigDecimal("9.5"),
                "comment", "初稿评语",
                "strengths", "结构清晰",
                "suggestions", "补充例子")).statusCode()).isEqualTo(200);
        Submission afterFirstEdit = submissions.findById(submissionId).orElseThrow();
        assertThat(afterFirstEdit.getSubjectiveTotal()).isEqualByComparingTo("9.5");
        assertThat(afterFirstEdit.getObjectiveTotal()).as("客观分不可手改").isEqualByComparingTo("10.00");

        // 再改一次文本，审计应留下「初稿评语 → 定稿评语」的逐行 diff
        Map<String, Object> edited = send(teacher, "PUT", path, Map.of(
                "subjectivePoints", new BigDecimal("8"),
                "comment", "定稿评语",
                "strengths", "结构清晰",
                "suggestions", "补充一个具体例子"));
        assertThat(edited.get("status")).isEqualTo("EDITED");
        assertThat(edited.get("originalSubjective")).isNotNull();
        assertThat(submissions.findById(submissionId).orElseThrow().getSubjectiveTotal())
                .isEqualByComparingTo("8");

        List<AuditLog> auditRows = auditLogs.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                "submission", submissionId.toString());
        AuditLog editAudit = auditRows.stream()
                .filter(a -> "review:edited".equals(a.getAction()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("编辑没有写审计"));
        assertThat(editAudit.getDetail())
                .contains("beforeSubjective=")
                .contains("afterSubjective=8")
                .contains("beforeComment=初稿评语")
                .contains("afterComment=定稿评语")
                .contains("textDiff")
                .contains("- 初稿评语")
                .contains("+ 定稿评语");

        // ④ 发布：EDITED 可发布，写 published_at 与审计
        Map<String, Object> published = post(teacher, path + "/publish", Map.of());
        assertThat(published.get("status")).isEqualTo("EDITED");
        assertThat(published.get("published")).isEqualTo(true);
        assertThat(published.get("publishedAt")).isNotNull();
        assertThat(auditLogs.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                "submission", submissionId.toString()))
                .extracting(AuditLog::getAction)
                .contains("review:publish");

        // ⑤ 发布后编辑 → 409；重复发布 → 409
        assertThat(raw(teacher, "PUT", path, Map.of("comment", "发布后改")).statusCode())
                .isEqualTo(409);
        assertThat(raw(teacher, "POST", path + "/publish", Map.of()).statusCode()).isEqualTo(409);

        // ⑥ 撤回：published_at 清空，未发布再撤回 → 409；状态保留 EDITED 可再次发布
        Map<String, Object> withdrawn = post(teacher, path + "/withdraw", Map.of());
        assertThat(withdrawn.get("published")).isEqualTo(false);
        assertThat(withdrawn.get("publishedAt")).isNull();
        assertThat(raw(teacher, "POST", path + "/withdraw", Map.of()).statusCode()).isEqualTo(409);
        assertThat(post(teacher, path + "/publish", Map.of()).get("published")).isEqualTo(true);

        // ⑦ 学生访问复核接口 → 403
        String student = login("student@teachloop.local");
        assertThat(raw(student, "GET", path, null).statusCode()).isEqualTo(403);
        assertThat(raw(student, "PUT", path, Map.of("comment", "x")).statusCode()).isEqualTo(403);
        assertThat(raw(student, "POST", path + "/publish", Map.of()).statusCode()).isEqualTo(403);
        assertThat(raw(student, "POST", path + "/withdraw", Map.of()).statusCode()).isEqualTo(403);
        assertThat(raw(student, "POST", "/api/grading/bulk-approve",
                Map.of("submissionIds", List.of(submissionId.toString()))).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("T250：通知带可跳转目标——布置通知是 exam+布置 id，反馈通知是 offering+布置 id")
    void notificationsCarryNavigableTarget() throws Exception {
        Fixture f = fixture();
        String studentCookie = f.studentA().cookie();

        // ① 布置发布：考试的通知要指向 /exams/{offeringId}
        Map<?, ?> published = notificationOfKind(studentCookie, "offering_published");
        assertThat(published.get("targetType")).isEqualTo("exam");
        assertThat(published.get("targetId")).isEqualTo(f.offeringId());

        // ② 交卷 → 批改 → 复核通过 → 发布反馈
        Map<String, Object> submitted = submit(f.studentA(), f.offeringId(),
                answers(1, "A", 2, "B"), "我的作文内容。");
        UUID submissionId = UUID.fromString(submitted.get("submissionId").toString());
        dispatchAndGrade(f.offeringId());
        String path = "/api/submissions/" + submissionId + "/review";
        assertThat(raw(teacher, "PUT", path, Map.of(
                "subjectivePoints", new BigDecimal("8"), "comment", "发布用评语")).statusCode()).isEqualTo(200);
        assertThat(post(teacher, path + "/publish", Map.of()).get("published")).isEqualTo(true);

        // ③ 反馈通知要指向 /feedback/{offeringId}；存 submissionId 会跳错页
        Map<?, ?> feedback = notificationOfKind(studentCookie, "feedback_published");
        assertThat(feedback.get("targetType")).isEqualTo("offering");
        assertThat(feedback.get("targetId"))
                .as("反馈页的参数是布置 id，不是提交 id")
                .isEqualTo(f.offeringId())
                .isNotEqualTo(submissionId.toString());
    }

    private Map<?, ?> notificationOfKind(String cookie, String kind) throws Exception {
        List<?> all = Json.parseList(raw(cookie, "GET", "/api/notifications", null).body());
        return all.stream()
                .map(o -> (Map<?, ?>) o)
                .filter(m -> kind.equals(m.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 kind=" + kind + " 的通知：" + all));
    }

    @Test
    @DisplayName("T104：批量通过逐份审计，无复核/已决策的提交计入 skipped")
    void bulkApproveCounts() throws Exception {
        Fixture f = fixture();
        UUID a = UUID.fromString(submit(f.studentA(), f.offeringId(),
                answers(1, "A", 2, "B"), "甲作文。").get("submissionId").toString());
        UUID b = UUID.fromString(submit(f.studentB(), f.offeringId(),
                answers(1, "C", 2, "C"), "乙作文。").get("submissionId").toString());
        dispatchAndGrade(f.offeringId());
        assertThat(reviews.findBySubmissionId(a).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.PENDING);
        assertThat(reviews.findBySubmissionId(b).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.PENDING);

        UUID missing = UUID.randomUUID();
        Map<String, Object> first = post(teacher, "/api/grading/bulk-approve", Map.of(
                "submissionIds", List.of(a.toString(), b.toString(), missing.toString())));
        assertThat(first.get("approved")).isEqualTo(2);
        assertThat(first.get("skipped")).as("没有复核记录的提交跳过").isEqualTo(1);
        assertThat(first.get("total")).isEqualTo(3);
        assertThat(reviews.findBySubmissionId(a).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.APPROVED);
        assertThat(reviews.findBySubmissionId(b).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.APPROVED);

        // 每份独立审计
        for (UUID id : List.of(a, b)) {
            assertThat(auditLogs.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                    "submission", id.toString()))
                    .extracting(AuditLog::getAction)
                    .contains("review:approved");
        }

        // 已决策的不再重复通过
        Map<String, Object> second = post(teacher, "/api/grading/bulk-approve", Map.of(
                "submissionIds", List.of(a.toString(), b.toString())));
        assertThat(((Number) second.get("approved")).intValue()).isZero();
        assertThat(((Number) second.get("skipped")).intValue()).isEqualTo(2);
        assertThat(((Number) second.get("total")).intValue()).isEqualTo(2);
    }

    // ================= fixtures / helpers =================

    private record StudentInfo(String cookie, String displayName) {
    }

    private record Fixture(String classId, String offeringId, StudentInfo studentA, StudentInfo studentB) {
    }

    /** 1 份 2 单选（各 10 分）+ 1 作文（10 分）的已发布试卷、1 个班（2 名学生）、1 场已发布考试。 */
    private Fixture fixture() throws Exception {
        String paperId = post(teacher, "/api/papers",
                Map.of("title", "复核测试卷 " + UUID.randomUUID(), "defaultDurationMinutes", 60), "id");
        String sectionId = post(teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "选择与写作", "pointsPerItem", 10), "id");
        addItem(paperId, sectionId, 1, "SINGLE_CHOICE", "第 1 题题干",
                "[\"A\",\"B\",\"C\"]", "A", 10);
        addItem(paperId, sectionId, 2, "SINGLE_CHOICE", "第 2 题题干",
                "[\"A\",\"B\",\"C\"]", "B", 10);
        addItem(paperId, sectionId, 3, "ESSAY", "作文题：写一段说明。", null, null, 10);
        post(teacher, "/api/papers/" + paperId + "/publish", Map.of());

        String classId = post(teacher, "/api/classes",
                Map.of("name", "复核班 " + UUID.randomUUID()), "id");
        String invite = classInvite(classId);
        StudentInfo a = newStudent("复核学生甲");
        StudentInfo b = newStudent("复核学生乙");
        join(a.cookie(), invite);
        join(b.cookie(), invite);

        Map<String, Object> body = new HashMap<>();
        body.put("paperId", paperId);
        body.put("timingMode", "INDIVIDUAL");
        body.put("dueAt", Instant.now().plus(7, ChronoUnit.DAYS).toString());
        body.put("durationMinutes", 60);
        body.put("classes", List.of(Map.of("classId", classId)));
        String offeringId = send(teacher, "POST", "/api/offerings", body).get("id").toString();
        send(teacher, "POST", "/api/offerings/" + offeringId + "/publish", Map.of());
        return new Fixture(classId, offeringId, a, b);
    }

    private void dispatchAndGrade(String offeringId) throws Exception {
        send(teacher, "POST", "/api/offerings/" + offeringId + "/grading/dispatch", Map.of());
        worker.processBatch(10);
    }

    private void addItem(String paperId, String sectionId, int number, String questionType,
                         String stem, String options, String answerKey, int points) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("number", number);
        body.put("questionType", questionType);
        body.put("stem", stem);
        body.put("options", options);
        body.put("answerKey", answerKey);
        body.put("points", points);
        body.put("sectionId", sectionId);
        post(teacher, "/api/papers/" + paperId + "/items", body, "id");
    }

    private Map<Integer, String> answers(Object... numberAnswerPairs) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (int i = 0; i < numberAnswerPairs.length; i += 2) {
            out.put((Integer) numberAnswerPairs[i], (String) numberAnswerPairs[i + 1]);
        }
        return out;
    }

    private Map<String, Object> submit(StudentInfo student, String offeringId,
                                       Map<Integer, String> answers, String contentText)
            throws Exception {
        send(student.cookie(), "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, String> e : answers.entrySet()) {
            rows.add(Map.of("number", e.getKey(), "answer", e.getValue()));
        }
        return send(student.cookie(), "POST", "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", contentText, "answers", rows));
    }

    private String classInvite(String classId) throws Exception {
        for (Object o : getList("/api/classes")) {
            Map<?, ?> c = (Map<?, ?>) o;
            if (classId.equals(c.get("id"))) {
                return c.get("inviteCode").toString();
            }
        }
        throw new AssertionError("找不到班级 " + classId);
    }

    private void join(String cookie, String inviteCode) throws Exception {
        assertThat(inviteCode).isNotNull();
        send(cookie, "POST", "/api/classes/join", Map.of("inviteCode", inviteCode));
    }

    private StudentInfo newStudent(String displayName) throws Exception {
        String email = "review-" + UUID.randomUUID() + "@teachloop.local";
        users.save(new User(UUID.randomUUID(), UserRole.STUDENT, email,
                encoder.encode("teachloop123"), displayName, Instant.now()));
        return new StudentInfo(login(email), displayName);
    }

    private String login(String email) throws Exception {
        HttpResponse<String> res = raw(null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(res.statusCode()).as("login %s: %s", email, res.body()).isEqualTo(200);
        List<String> cookies = res.headers().allValues("set-cookie");
        return cookies.stream()
                .map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("登录没有下发会话 cookie：" + cookies));
    }

    private Map<String, Object> get(String cookie, String path) throws Exception {
        return send(cookie, "GET", path, null);
    }

    private List<?> getList(String path) throws Exception {
        HttpResponse<String> res = raw(teacher, "GET", path, null);
        assertThat(res.statusCode()).withFailMessage("GET %s -> %s %s", path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parseList(res.body());
    }

    private Map<String, Object> post(String cookie, String path, Object body) throws Exception {
        return send(cookie, "POST", path, body);
    }

    private String post(String cookie, String path, Object body, String field) throws Exception {
        Object value = send(cookie, "POST", path, body).get(field);
        assertThat(value).as("%s 返回缺少字段 %s", path, field).isNotNull();
        return value.toString();
    }

    private Map<String, Object> send(String cookie, String method, String path, Object body)
            throws Exception {
        HttpResponse<String> res = raw(cookie, method, path, body);
        assertThat(res.statusCode()).withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private HttpResponse<String> raw(String cookie, String method, String path, Object body)
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

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER =
                new tools.jackson.databind.ObjectMapper();

        static String write(Object o) {
            return MAPPER.writeValueAsString(o);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) {
            return MAPPER.readValue(s, Map.class);
        }

        static List<?> parseList(String s) {
            return MAPPER.readValue(s, List.class);
        }
    }
}
