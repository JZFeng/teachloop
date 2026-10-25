package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.DynamicPropertyRegistry;
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

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("就绪契约：无 provider 时 409 并提示联系管理员；配好 provider 后放行")
    void readinessGatesManualDispatch() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

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

        // ---------- 2. 建好一份已交卷的作业：手动派发在未配置时 409，且状态不变 ----------
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

        // ---------- 3. 管理员建 provider（自动成为当前模型）→ configured=true, ready=true ----------
        String admin = createAdmin(base);
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

        // ---------- 4. 同一派发请求现在 200，并且真的入队 ----------
        HttpResponse<String> dispatched = raw(base, teacher, "POST",
                "/api/offerings/" + offeringId + "/grading/dispatch", Map.of(), "zh-CN");
        assertThat(dispatched.statusCode()).withFailMessage(dispatched.body()).isEqualTo(200);
        Map<String, Object> body = Json.parse(dispatched.body());
        assertThat(body.get("offeringId")).isEqualTo(offeringId);
        assertThat(((Number) body.get("enqueued")).intValue()).isEqualTo(1);
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
