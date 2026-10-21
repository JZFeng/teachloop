package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.grading.domain.Review;
import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.grading.domain.ReviewStatus;
import com.teachloop.grading.service.GradingWorker;
import com.teachloop.observability.domain.AuditLog;
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
import org.junit.jupiter.api.BeforeEach;
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
 * T243 / D-15 验收：AI 只是可选的辅助，老师可以对任何已交卷提交人工判分并发布；AI 晚到不得覆盖人工结果。
 *
 * <p>四条链路：
 * <ol>
 *   <li>不派发 AI，老师直接 GET 复核详情（懒建 review，AI 草稿为 null）→ PUT 主观分 → 通过 → 发布
 *       → 学生反馈能看到分数与评语；</li>
 *   <li>人工判分后模拟派发让 worker 跑 AI：worker 因老师已判分而不覆盖（否则 stub 会把 12 改成 15）；</li>
 *   <li>AI 失败（FAILED + grading_error）时人工兜底：填分、通过、发布，且 grading_error 被清空；</li>
 *   <li>批量通过跳过「未填分的人工复核」，避免把没判分的提交当 0 分放行。</li>
 * </ol>
 *
 * <p>学生用 seed 账号 student01@teachloop.local（teachloop.seed.demo=true），每个用例自建班级与作业，互不干扰。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "teachloop.seed.demo=true")
@Testcontainers
class ManualGradingTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // 批改/派发/清扫只在测试里手动跑，关掉后台定时，避免与断言抢时序
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    private static final String STUDENT_EMAIL = "student01@teachloop.local";

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SubmissionRepository submissions;

    @Autowired
    ReviewRepository reviews;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    GradingWorker worker;

    private final HttpClient client = HttpClient.newHttpClient();

    private String base;
    private String teacher;
    private String student;

    @BeforeEach
    void setUp() throws Exception {
        base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        teacher = login("teacher@teachloop.local");
        student = login(STUDENT_EMAIL);
    }

    @Test
    @DisplayName("T243：不派发 AI，老师直接人工判分 17.5 → 通过 → 发布，学生反馈可见")
    void manualGradingWithoutAi() throws Exception {
        Fixture f = fixture("人工判分作业");
        String path = "/api/submissions/" + f.submissionId() + "/review";

        // ① 复核详情：没有 AI 草稿也能打开（review 懒建），当前客观/主观分都是 0
        Map<String, Object> detail = get(teacher, path);
        assertThat(((Map<?, ?>) detail.get("review")).get("status")).isEqualTo("PENDING");
        assertThat(detail.get("aiSubjectiveDraft")).as("AI 未参与时没有主观草稿").isNull();
        assertThat(decimal(detail.get("objectivePoints"))).isEqualByComparingTo("0");
        assertThat(decimal(detail.get("subjectivePoints"))).isEqualByComparingTo("0");
        assertThat(detail.get("contentText")).isEqualTo("人工判分用的作文。" + f.nonce());

        // ② 老师填主观分与三段文本 → EDITED，并经端口回写 submission
        Map<String, Object> edited = send(teacher, "PUT", path, Map.of(
                "subjectivePoints", new BigDecimal("17.5"),
                "comment", "论证完整，例子具体。",
                "strengths", "结构清晰",
                "suggestions", "结尾可以收得更紧"));
        assertThat(edited.get("status")).isEqualTo("EDITED");
        assertThat(edited.get("originalSubjective")).as("人工判分不产生 AI 草稿").isNull();
        Submission afterEdit = submissions.findById(f.submissionId()).orElseThrow();
        assertThat(afterEdit.getGradingState()).as("填分即完成批改").isEqualTo(GradingState.DONE);
        assertThat(afterEdit.getSubjectiveTotal()).isEqualByComparingTo("17.5");
        assertThat(afterEdit.getObjectiveTotal()).as("客观分不可手改").isEqualByComparingTo("0");

        // 审计：标记这是纯人工判分，并记录改前实际分与 AI 草稿（null）
        AuditLog editAudit = auditLogs.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                        "submission", f.submissionId().toString()).stream()
                .filter(a -> "review:edited".equals(a.getAction()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("人工判分没有写审计"));
        assertThat(editAudit.getDetail())
                .contains("manual=true")
                .contains("aiSubjectiveDraft=null")
                .contains("beforeSubjective=0")
                .contains("afterSubjective=17.5");

        // ③ 通过 + 发布：人工判分保存后是 EDITED，可直接发布
        assertThat(post(teacher, path, Map.of("status", "APPROVED", "comment", "定稿评语"))
                .get("status")).isEqualTo("APPROVED");
        Map<String, Object> published = post(teacher, path + "/publish", Map.of());
        assertThat(published.get("published")).isEqualTo(true);

        // ④ 学生反馈：见到 17.5 与评语
        Map<String, Object> feedback = get(student, "/api/offerings/" + f.offeringId() + "/my-feedback");
        assertThat(feedback.get("feedbackPublished")).isEqualTo(true);
        Map<?, ?> latest = (Map<?, ?>) feedback.get("latestAttempt");
        assertThat(decimal(latest.get("subjectiveScore"))).isEqualByComparingTo("17.5");
        assertThat(((Map<?, ?>) feedback.get("review")).get("comment")).isEqualTo("定稿评语");
    }

    @Test
    @DisplayName("T243：人工分 12 不被后到的 AI 覆盖（worker 跳过已人工判分的提交）")
    void manualScoreSurvivesLateAi() throws Exception {
        Fixture f = fixture("人工分不被覆盖作业");
        String path = "/api/submissions/" + f.submissionId() + "/review";

        // ① 人工判 12 分 → EDITED
        send(teacher, "GET", path, null);
        assertThat(send(teacher, "PUT", path, Map.of(
                "subjectivePoints", new BigDecimal("12"),
                "comment", "人工分 12")).get("status")).isEqualTo("EDITED");
        assertThat(submissions.findById(f.submissionId()).orElseThrow().getSubjectiveTotal())
                .isEqualByComparingTo("12");

        // ② 模拟派发把这一份重新放回队列（作业总分 20，stub 会给 0.75*20=15）
        jdbc.update("update submissions set grading_state = 'PENDING' where id = ?",
                f.submissionId());
        Map<String, Object> dispatch = post(teacher,
                "/api/offerings/" + f.offeringId() + "/grading/dispatch", Map.of());
        assertThat(((Number) dispatch.get("enqueued")).intValue())
                .as("没有 QUEUED 行，派发本身不入队").isZero();

        worker.processBatch(10);

        // ③ AI 不得覆盖：分数仍是 12，复核仍是 EDITED、仍未发布
        Submission afterWorker = submissions.findById(f.submissionId()).orElseThrow();
        assertThat(afterWorker.getSubjectiveTotal()).as("AI 结果必须被丢弃").isEqualByComparingTo("12");
        assertThat(afterWorker.getGradingState()).as("worker 不认领已人工判分的提交")
                .isEqualTo(GradingState.PENDING);
        Review review = reviews.findBySubmissionId(f.submissionId()).orElseThrow();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.EDITED);
        assertThat(review.getOriginalSubjective()).as("人工判分不写 AI 草稿").isNull();
    }

    @Test
    @DisplayName("T243：AI 失败（FAILED）也能人工兜底，填分后通过并发布，错误信息被清空")
    void manualGradingRescuesFailedSubmission() throws Exception {
        Fixture f = fixture("AI 失败兜底作业");
        jdbc.update("update submissions set grading_state = 'FAILED', grading_error = 'boom' "
                + "where id = ?", f.submissionId());

        String path = "/api/submissions/" + f.submissionId() + "/review";
        Map<String, Object> detail = get(teacher, path);
        assertThat(((Map<?, ?>) detail.get("review")).get("status")).isEqualTo("PENDING");

        assertThat(send(teacher, "PUT", path, Map.of(
                "subjectivePoints", new BigDecimal("9"),
                "comment", "AI 失败后人工判分")).get("status")).isEqualTo("EDITED");
        assertThat(post(teacher, path, Map.of("status", "APPROVED", "comment", "定稿"))
                .get("status")).isEqualTo("APPROVED");
        assertThat(post(teacher, path + "/publish", Map.of()).get("published")).isEqualTo(true);

        Submission after = submissions.findById(f.submissionId()).orElseThrow();
        assertThat(after.getGradingState()).isEqualTo(GradingState.DONE);
        assertThat(after.getSubjectiveTotal()).isEqualByComparingTo("9");
        assertThat(after.getGradingError()).as("人工判分成功后清空失败原因").isNull();
    }

    @Test
    @DisplayName("T243：批量通过跳过未填分的人工复核（不能当 0 分放行）")
    void bulkApproveSkipsUnscoredManualReview() throws Exception {
        Fixture f = fixture("批量通过跳过作业");
        // 老师打开了复核详情（懒建 PENDING 复核），但没填分
        get(teacher, "/api/submissions/" + f.submissionId() + "/review");

        Map<String, Object> result = post(teacher, "/api/grading/bulk-approve",
                Map.of("submissionIds", List.of(f.submissionId().toString())));
        assertThat(((Number) result.get("approved")).intValue()).isZero();
        assertThat(((Number) result.get("skipped")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("total")).intValue()).isEqualTo(1);

        assertThat(reviews.findBySubmissionId(f.submissionId()).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    @DisplayName("T243：老师先打开详情（无草稿）后 AI 才完成，草稿被补记且不覆盖人工分")
    void aiDraftCapturedIfTeacherOpenedFirst() throws Exception {
        Fixture f = fixture("先开详情后 AI 作业");
        String path = "/api/submissions/" + f.submissionId() + "/review";

        // 打开详情会懒建 PENDING、original_subjective=null；此时还没派发。
        assertThat(get(teacher, path).get("aiSubjectiveDraft")).isNull();

        send(teacher, "POST", "/api/offerings/" + f.offeringId() + "/grading/dispatch", Map.of());
        worker.processBatch(10);

        Review r = reviews.findBySubmissionId(f.submissionId()).orElseThrow();
        assertThat(r.getOriginalSubjective()).as("AI 完成后补记草稿").isEqualByComparingTo("15");
        assertThat(r.getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(r.getTeacherId()).isNull();

        Map<String, Object> after = get(teacher, path);
        assertThat(decimal(after.get("aiSubjectiveDraft"))).isEqualByComparingTo("15");
        assertThat(decimal(after.get("subjectivePoints"))).isEqualByComparingTo("15");
    }

    // ================= fixtures / helpers =================

    /** 一个班级 + 一份已发布的独立作业（总分 20）+ 学生已交卷；不派发，不跑 worker。 */
    private record Fixture(String classId, String offeringId, UUID submissionId, String nonce) {
    }

    private Fixture fixture(String title) throws Exception {
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> cls = post(teacher, "/api/classes", Map.of("name", title + "班 " + nonce));
        String classId = cls.get("id").toString();
        String invite = cls.get("inviteCode").toString();
        String offeringId = post(teacher, "/api/offerings", Map.of(
                "title", title + " " + nonce,
                "instructions", "请写一段 100 词的短文，说明你的暑假安排。",
                "totalPoints", 20,
                "rubricMarkdown", "# 评分标准\n- 内容要点：切题\n- 语言准确性：语法正确",
                "dueAt", "2026-12-31T23:59:59Z",
                "timingMode", "INDIVIDUAL",
                "classes", List.of(Map.of("classId", classId)),
                "publish", true), "id");
        send(student, "POST", "/api/classes/join", Map.of("inviteCode", invite));
        String submissionId = submit(offeringId, "人工判分用的作文。" + nonce);
        return new Fixture(classId, offeringId, UUID.fromString(submissionId), nonce);
    }

    private String submit(String offeringId, String contentText) throws Exception {
        send(student, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        Object submissionId = send(student, "POST", "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", contentText)).get("submissionId");
        assertThat(submissionId).as("提交没有返回 submissionId").isNotNull();
        return submissionId.toString();
    }

    private BigDecimal decimal(Object value) {
        assertThat(value).as("分数为 null").isNotNull();
        return new BigDecimal(value.toString());
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
    }
}
