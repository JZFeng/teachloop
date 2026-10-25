package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import com.teachloop.sitting.service.OfferingGradingDispatcher;
import com.teachloop.sitting.service.TeacherGradingPreferenceService;
import com.teachloop.sitting.service.TeacherGradingScheduler;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AI 就绪契约与手动派发闸门（唯一 engine=ai 的 app 测试）。
 *
 * <p>其余 app 测试都用 stub 引擎：stub 是开发/QA 替身，{@code ready} 恒 true，不能被拦。
 * 这里显式把 {@code DEEPSEEK_API_KEY} 置空，保证「没有 provider」时环境变量不会把 configured 顶成 true。
 *
 * <p>只断言 HTTP 契约（200/403/409、字段与入队份数），不等批改结果：poll 间隔设到 1 小时，
 * worker 不会在测试窗口内真的调用模型。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AiReadinessTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // 本测试要验证真实引擎的就绪判断；stub 的 ready 恒 true，会把「未配置」掩盖掉。
        r.add("teachloop.grading.engine", () -> "ai");
        // 关掉三个后台作业与考试扫描，避免测试里真的调用模型或提前派发。
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
        r.add("teachloop.security.secret", () -> "test-secret-for-ai-readiness");
        // 明确「没有环境变量 Key」：否则跑测试的机器 export 了真 Key 会让断言变成偶然通过。
        r.add("DEEPSEEK_API_KEY", () -> "");
    }

    @Autowired
    Environment env;

    @Autowired
    OfferingGradingDispatcher dispatcher;

    @Autowired
    TeacherGradingScheduler scheduler;

    @Autowired
    TeacherGradingPreferenceService preferences;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("AI 就绪契约 + 手动/自动派发闸门：未就绪 quick fail（日志+通知），提交留在 QUEUED，配好后自动继续")
    void readinessGatesManualAndAutomaticDispatch() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");
        UUID teacherId = userId("teacher@teachloop.local");

        // ---------- 1. 没有任何 provider：configured=false, ready=false, engine=ai ----------
        HttpResponse<String> before = raw(base, teacher, "GET", "/api/ai-readiness", null, "zh-CN");
        assertThat(before.statusCode()).withFailMessage(before.body()).isEqualTo(200);
        Map<String, Object> noProvider = Json.parse(before.body());
        assertThat(noProvider.get("configured")).isEqualTo(false);
        assertThat(noProvider.get("ready")).isEqualTo(false);
        assertThat(noProvider.get("engine")).isEqualTo("ai");
        assertThat(noProvider.get("source")).isNull();
        assertThat(noProvider.get("provider")).isNull();
        assertThat(noProvider.get("model")).isNull();
        assertThat(before.body()).as("绝不回显 Key").doesNotContain("apiKey").doesNotContain("sk-");

        // 学生永远不直接调 AI：403。
        assertThat(raw(base, student, "GET", "/api/ai-readiness", null, "zh-CN").statusCode())
                .as("学生读就绪状态应 403").isEqualTo(403);

        // ---------- 2. 建班/交卷：手动派发在未配置时 409，且状态不变 ----------
        String classId = createClass(base, teacher, "AI 就绪测试班");
        String invite = classInvite(base, teacher, classId);
        join(base, student, invite);
        String offeringId = createOffering(base, teacher, classId);
        String submissionId = submitAs(base, student, offeringId);
        assertThat(gradingState(submissionId)).isEqualTo("QUEUED");

        HttpResponse<String> blocked = raw(base, teacher, "POST",
                "/api/offerings/" + offeringId + "/grading/dispatch", Map.of(), "zh-CN");
        assertThat(blocked.statusCode()).withFailMessage(blocked.body()).isEqualTo(409);
        assertThat(Json.parse(blocked.body()).get("code")).isEqualTo("CONFLICT");
        assertThat(String.valueOf(Json.parse(blocked.body()).get("message")))
                .as("界面要能提示联系管理员设置 AI").contains("联系管理员");

        // 拦在入队之前：没有 run、提交仍在 QUEUED（不能先入队再让 worker 失败）。
        assertThat(runCount(offeringId)).isZero();
        assertThat(gradingState(submissionId)).isEqualTo("QUEUED");

        // 单份「让 AI 先批」走同一个闸门。
        HttpResponse<String> blockedOne = raw(base, teacher, "POST",
                "/api/offerings/" + offeringId + "/submissions/" + submissionId + "/grading/dispatch",
                Map.of(), "zh-CN");
        assertThat(blockedOne.statusCode()).withFailMessage(blockedOne.body()).isEqualTo(409);
        assertThat(gradingState(submissionId)).isEqualTo("QUEUED");

        // ---------- 3. 自动派发（夜间作业）同样 quick fail：不入队、记日志、通知管理员与老师 ----------
        String admin = createAdmin(base);
        UUID adminId = userId("ai-readiness-admin@teachloop.local");
        preferences.update(teacherId, teacherId,
                new TeacherGradingPreferenceService.UpdateRequest("FIXED_TIME", "00:00", "Asia/Shanghai", true));
        scheduler.scan(Instant.now());
        assertThat(gradingState(submissionId)).as("AI 未就绪时自动派发不推进队列").isEqualTo("QUEUED");
        assertThat(runCount(offeringId)).isZero();
        assertThat(preferences.view(teacherId).lastFixedRunAt())
                .as("quick fail 不消耗今天的固定时刻，配置后下一轮扫描继续").isNull();
        assertThat(aiNotReadyRecipients()).as("管理员与相关老师各一条通知")
                .containsExactlyInAnyOrder(adminId.toString(), teacherId.toString());

        // 扫描每 60 秒一轮：同一个未就绪周期不重复通知。
        scheduler.scan(Instant.now());
        assertThat(aiNotReadyCount()).as("不重复通知").isEqualTo(2);

        // ---------- 4. 考试自动派发路径：同样跳过，不产生 run ----------
        String examId = createExamOffering(base, teacher, classId);
        String examSubmission = submitAs(base, student, examId);
        jdbc.update("update offerings set due_at = ?, grace_hours = 0 where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), UUID.fromString(examId));
        assertThat(dispatcher.autoDispatch()).isZero();
        assertThat(gradingState(examSubmission)).as("考试提交也留在 QUEUED").isEqualTo("QUEUED");
        assertThat(runCount(examId)).isZero();
        assertThat(aiNotReadyCount()).as("同一未就绪周期仍只有两条通知").isEqualTo(2);

        // ---------- 5. 管理员建 provider（自动成为当前模型）→ configured=true, ready=true ----------
        HttpResponse<String> created = raw(base, admin, "POST", "/api/admin/ai-providers",
                Map.of("providerId", "readiness-local", "displayName", "Local Test",
                        "baseUrl", "http://127.0.0.1:8080/v1", "protocol", "openai",
                        "apiKey", "sk-readiness-test-1234", "model", "qwen3.8"), "zh-CN");
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(200);

        Map<String, Object> ready = Json.parse(
                raw(base, teacher, "GET", "/api/ai-readiness", null, "zh-CN").body());
        assertThat(ready.get("configured")).isEqualTo(true);
        assertThat(ready.get("ready")).isEqualTo(true);
        assertThat(ready.get("engine")).isEqualTo("ai");
        assertThat(ready.get("source")).isEqualTo("provider");
        assertThat(ready.get("provider")).isEqualTo("readiness-local");
        assertThat(ready.get("model")).isEqualTo("qwen3.8");

        // ---------- 6. 配好之后自动派发自己继续：考试按到期派发、作业按固定时刻派发 ----------
        assertThat(dispatcher.autoDispatch()).isEqualTo(1);
        assertThat(gradingState(examSubmission)).isEqualTo("PENDING");
        assertThat(runCount(examId)).isEqualTo(1);

        scheduler.scan(Instant.now());
        assertThat(gradingState(submissionId)).isEqualTo("PENDING");
        assertThat(runCount(offeringId)).isEqualTo(1);
        assertThat(preferences.view(teacherId).lastFixedRunAt()).isNotNull();

        // ---------- 7. 手动派发此时 200；已派发过所以幂等 enqueued=0 ----------
        HttpResponse<String> dispatched = raw(base, teacher, "POST",
                "/api/offerings/" + offeringId + "/grading/dispatch", Map.of(), "zh-CN");
        assertThat(dispatched.statusCode()).withFailMessage(dispatched.body()).isEqualTo(200);
        Map<String, Object> body = Json.parse(dispatched.body());
        assertThat(body.get("offeringId")).isEqualTo(offeringId);
        assertThat(((Number) body.get("enqueued")).intValue()).isZero();
        assertThat(gradingState(submissionId)).isEqualTo("PENDING");
        assertThat(runCount(offeringId)).isEqualTo(1);
    }

    // ================= fixtures / helpers =================

    private String createAdmin(String base) throws Exception {
        HttpResponse<String> res = raw(base, null, "POST", "/api/setup/admin",
                Map.of("email", "ai-readiness-admin@teachloop.local", "password", "readiness-pass-123",
                        "displayName", "AI 就绪测试管理员"), "zh-CN");
        assertThat(res.statusCode()).withFailMessage(res.body()).isEqualTo(200);
        return cookie(res);
    }

    private String createClass(String base, String teacher, String name) throws Exception {
        Map<String, Object> created = send(base, teacher, "POST", "/api/classes", Map.of("name", name));
        return created.get("id").toString();
    }

    private String classInvite(String base, String teacher, String classId) throws Exception {
        HttpResponse<String> res = raw(base, teacher, "GET", "/api/classes", null, "zh-CN");
        assertThat(res.statusCode()).isEqualTo(200);
        for (Object o : Json.parseList(res.body())) {
            Map<?, ?> c = (Map<?, ?>) o;
            if (classId.equals(c.get("id"))) {
                return c.get("inviteCode").toString();
            }
        }
        throw new AssertionError("找不到班级 " + classId);
    }

    private void join(String base, String student, String inviteCode) throws Exception {
        send(base, student, "POST", "/api/classes/join", Map.of("inviteCode", inviteCode));
    }

    /** 考试场次（引用试卷）：autoDispatch 只扫 EXAM，所以通知/闸门的考试路径要有它。 */
    private String createExamOffering(String base, String teacher, String classId) throws Exception {
        Map<String, Object> paper = new HashMap<>();
        paper.put("title", "AI 就绪测试试卷");
        paper.put("defaultDurationMinutes", 30);
        String paperId = send(base, teacher, "POST", "/api/papers", paper).get("id").toString();

        Map<String, Object> section = new HashMap<>();
        section.put("title", "作文");
        section.put("pointsPerItem", 10);
        String sectionId = send(base, teacher, "POST", "/api/papers/" + paperId + "/sections", section)
                .get("id").toString();

        Map<String, Object> item = new HashMap<>();
        item.put("number", 1);
        item.put("questionType", "ESSAY");
        item.put("stem", "Write a short letter.");
        item.put("points", 10);
        item.put("sectionId", sectionId);
        send(base, teacher, "POST", "/api/papers/" + paperId + "/items", item);
        send(base, teacher, "POST", "/api/papers/" + paperId + "/publish", Map.of());

        Map<String, Object> body = new HashMap<>();
        body.put("paperId", paperId);
        body.put("timingMode", "INDIVIDUAL");
        body.put("dueAt", Instant.now().plusSeconds(3600).toString());
        body.put("graceHours", 0);
        body.put("durationMinutes", 30);
        body.put("classes", List.of(Map.of("classId", classId)));
        String offeringId = send(base, teacher, "POST", "/api/offerings", body).get("id").toString();
        send(base, teacher, "POST", "/api/offerings/" + offeringId + "/publish", Map.of());
        return offeringId;
    }

    /** 作业自带内容（D-14）：不建卷、不带 paperId。 */
    private String createOffering(String base, String teacher, String classId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "AI 就绪测试作业");
        body.put("instructions", "Write a short letter.");
        body.put("totalPoints", 20);
        body.put("rubricMarkdown", "# Rubric\n- Content\n- Accuracy");
        body.put("timingMode", "INDIVIDUAL");
        body.put("dueAt", java.time.Instant.now().plusSeconds(3600).toString());
        body.put("graceHours", 3);
        body.put("classes", List.of(Map.of("classId", classId)));
        String offeringId = send(base, teacher, "POST", "/api/offerings", body).get("id").toString();
        send(base, teacher, "POST", "/api/offerings/" + offeringId + "/publish", Map.of());
        return offeringId;
    }

    private String submitAs(String base, String student, String offeringId) throws Exception {
        send(base, student, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        Map<String, Object> submission = send(base, student, "POST",
                "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", "This is a short letter for the AI readiness test."));
        return submission.get("submissionId").toString();
    }

    private String gradingState(String submissionId) {
        // 只读断言：交给 HTTP 进度接口更绕，这里直接用仓库层会引入额外装配；用 JDBC 最稳。
        return jdbc.queryForObject("select grading_state from submissions where id = ?", String.class,
                java.util.UUID.fromString(submissionId));
    }

    private int runCount(String offeringId) {
        Integer count = jdbc.queryForObject("select count(*) from grading_runs where offering_id = ?",
                Integer.class, java.util.UUID.fromString(offeringId));
        return count == null ? 0 : count;
    }

    private UUID userId(String email) {
        return UUID.fromString(jdbc.queryForObject("select id::text from users where email = ?",
                String.class, email));
    }

    private int aiNotReadyCount() {
        Integer count = jdbc.queryForObject(
                "select count(*) from notifications where kind = 'ai_not_ready'", Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> aiNotReadyRecipients() {
        return jdbc.queryForList(
                "select recipient_id::text from notifications where kind = 'ai_not_ready'", String.class);
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String login(String base, String email) throws Exception {
        HttpResponse<String> res = raw(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"), "zh-CN");
        assertThat(res.statusCode()).as("login %s: %s", email, res.body()).isEqualTo(200);
        return cookie(res);
    }

    private static String cookie(HttpResponse<String> res) {
        return res.headers().allValues("set-cookie").stream()
                .map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("没有下发会话 cookie"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpResponse<String> res = raw(base, cookie, method, path, body, "zh-CN");
        assertThat(res.statusCode())
                .withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private HttpResponse<String> raw(String base, String cookie, String method, String path,
                                     Object body, String language) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("Accept-Language", language);
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
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

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
