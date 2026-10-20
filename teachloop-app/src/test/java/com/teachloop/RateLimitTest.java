package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T037 验收：四类端点限流。
 *
 * <p>阈值通过 {@code DynamicPropertySource} 调到很小（登录 4 次 / 3 秒，其余 2 次 / 60 秒），
 * 生产默认值写在 {@code RateLimitSettings} 的 @Value 里，不改 application.yml。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RateLimitTest {

    private static final String PASSWORD = "teachloop123";
    private static final String OTHER_STUDENT = "rate-other@teachloop.local";

    private static final Path STORAGE_DIR = createTempDir();

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("teachloop-rate-uploads-");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.storage.local-dir", STORAGE_DIR::toString);
        // 限流阈值：登录 4 次 / 3 秒；上传、派发、事件各 2 次 / 60 秒
        r.add("teachloop.rate-limit.login.limit", () -> "4");
        r.add("teachloop.rate-limit.login.window-seconds", () -> "3");
        r.add("teachloop.rate-limit.upload.limit", () -> "2");
        r.add("teachloop.rate-limit.upload.window-seconds", () -> "60");
        r.add("teachloop.rate-limit.grading-dispatch.limit", () -> "2");
        r.add("teachloop.rate-limit.grading-dispatch.window-seconds", () -> "60");
        r.add("teachloop.rate-limit.events.limit", () -> "2");
        r.add("teachloop.rate-limit.events.window-seconds", () -> "60");
        // 后台作业与本测试无关
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    private final HttpClient client = HttpClient.newHttpClient();

    private String base;

    @BeforeEach
    void setUp() {
        base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        // 每个用例清空计数，避免跨用例互相影响
        jdbc.update("delete from rate_limits");
    }

    // ================= 登录：按 IP，登录前即生效 =================

    @Test
    @DisplayName("T037：登录连续超限返回 429 + RATE_LIMITED + Retry-After，窗口滚动后恢复")
    void loginRateLimitedThenRecoversAfterWindow() throws Exception {
        // 固定窗口按 epoch 秒对齐；等到窗口刚开始再打，避免 5 次中途跨过边界导致计数清零
        waitForWindowStart(3);
        for (int i = 0; i < 4; i++) {
            HttpResponse<String> attempt = loginRaw("teacher@teachloop.local", "wrong-password");
            assertThat(attempt.statusCode()).as("前 4 次进入登录逻辑（密码错误 401）").isEqualTo(401);
        }

        HttpResponse<String> limited = loginRaw("teacher@teachloop.local", "wrong-password");
        assertThat(limited.statusCode()).as("第 5 次在登录前被限流").isEqualTo(429);
        Map<String, Object> error = Json.parse(limited.body());
        assertThat(error.get("code")).isEqualTo("RATE_LIMITED");
        assertThat(error.get("message")).isNotNull();
        String retryAfter = limited.headers().firstValue("retry-after").orElse(null);
        assertThat(retryAfter).as("Retry-After 必须是秒数").isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 3L);

        Integer rows = jdbc.queryForObject(
                "select count(*) from rate_limits where action = 'login' and key like 'ip:%'", Integer.class);
        assertThat(rows).isPositive();

        // 窗口只有 3 秒：跨过边界后计数重新从 1 开始
        Thread.sleep(3300);
        HttpResponse<String> afterWindow = loginRaw("teacher@teachloop.local", "wrong-password");
        assertThat(afterWindow.statusCode()).as("新窗口不再 429").isEqualTo(401);
        assertThat(errorMessage(limited)).isNotBlank();
    }

    // ================= 上传：按用户，且用户之间互相独立 =================

    @Test
    @DisplayName("T037：上传按用户限流；用户 A 超限后用户 B 不受影响")
    void uploadRateLimitedPerUser() throws Exception {
        String teacher = login("teacher@teachloop.local");
        String studentA = login("student@teachloop.local");
        String studentB = createAndLoginOtherStudent();

        String classId = createClass(teacher, "限流班");
        String invite = classInvite(teacher, classId);
        join(studentA, invite);
        join(studentB, invite);
        String offeringId = createOffering(teacher, classId);

        // 第 1 次成功交卷
        assertThat(upload(studentA, offeringId, "a1.txt").statusCode()).isBetween(200, 299);
        // 第 2 次仍在配额内（已交卷 → 409，不是 429）
        assertThat(upload(studentA, offeringId, "a2.txt").statusCode()).isEqualTo(409);
        // 第 3 次超限
        HttpResponse<String> limited = upload(studentA, offeringId, "a3.txt");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(Json.parse(limited.body()).get("code")).isEqualTo("RATE_LIMITED");
        assertThat(limited.headers().firstValue("retry-after")).isPresent();

        // 另一个用户独立计数，仍可上传
        assertThat(upload(studentB, offeringId, "b1.txt").statusCode()).isBetween(200, 299);
    }

    // ================= 派发与事件：按用户、动作之间独立 =================

    @Test
    @DisplayName("T037：派发按用户限流；事件上报独立计数；未匹配的普通请求不受影响")
    void dispatchAndEventsAreIndependentAndNormalRequestsPass() throws Exception {
        String teacher = login("teacher@teachloop.local");
        String classId = createClass(teacher, "派发限流班");
        String offeringId = createOffering(teacher, classId);

        assertThat(send(teacher, "POST", "/api/offerings/" + offeringId + "/grading/dispatch", null)
                .statusCode()).isBetween(200, 299);
        assertThat(send(teacher, "POST", "/api/offerings/" + offeringId + "/grading/dispatch", null)
                .statusCode()).isBetween(200, 299);
        HttpResponse<String> dispatchLimited = raw(teacher, "POST",
                "/api/offerings/" + offeringId + "/grading/dispatch", null);
        assertThat(dispatchLimited.statusCode()).isEqualTo(429);
        assertThat(Json.parse(dispatchLimited.body()).get("code")).isEqualTo("RATE_LIMITED");

        // events 是另一个动作：派发已超限，事件仍可正常进入（当前端点可能尚未实现，允许 404）
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> event = raw(teacher, "POST", "/api/events",
                    Map.of("type", "page_view", "path", "/"));
            assertThat(event.statusCode()).as("事件计数独立于派发").isNotEqualTo(429);
        }
        HttpResponse<String> eventsLimited = raw(teacher, "POST", "/api/events", Map.of("type", "page_view"));
        assertThat(eventsLimited.statusCode()).isEqualTo(429);
        assertThat(Json.parse(eventsLimited.body()).get("code")).isEqualTo("RATE_LIMITED");
        assertThat(eventsLimited.headers().firstValue("retry-after")).isPresent();

        // 未登录访问受保护接口仍然由授权层返回 401（限流不吞掉 401）
        assertThat(raw(null, "GET", "/api/offerings", null).statusCode()).isEqualTo(401);

        // 未被限流覆盖的普通请求反复调用也不受影响
        for (int i = 0; i < 10; i++) {
            assertThat(send(teacher, "GET", "/api/offerings", null).statusCode()).isEqualTo(200);
        }
    }

    // ================= fixtures / helpers =================

    /** 等到下一个固定窗口起点之后 100ms，保证接下来的连续请求落在同一个窗口内。 */
    private void waitForWindowStart(long windowSeconds) throws InterruptedException {
        long windowMillis = windowSeconds * 1000L;
        long wait = windowMillis - (System.currentTimeMillis() % windowMillis) + 100L;
        Thread.sleep(wait);
    }

    private String errorMessage(HttpResponse<String> response) {
        return Json.parse(response.body()).get("message").toString();
    }

    private HttpResponse<String> loginRaw(String email, String password) throws Exception {
        return raw(null, "POST", "/api/auth/login", Map.of("email", email, "password", password));
    }

    private String login(String email) throws Exception {
        HttpResponse<String> res = loginRaw(email, PASSWORD);
        assertThat(res.statusCode()).as("login %s: %s", email, res.body()).isEqualTo(200);
        List<String> cookies = res.headers().allValues("set-cookie");
        return cookies.stream()
                .map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("登录没有下发会话 cookie：" + cookies));
    }

    private String createAndLoginOtherStudent() throws Exception {
        if (users.findByEmail(OTHER_STUDENT).isEmpty()) {
            users.save(new User(UUID.randomUUID(), UserRole.STUDENT, OTHER_STUDENT, encoder.encode(PASSWORD),
                    "限流学生乙", Instant.now()));
        }
        return login(OTHER_STUDENT);
    }

    private String createClass(String teacher, String name) throws Exception {
        return post(teacher, "/api/classes", Map.of("name", name), "id");
    }

    private String classInvite(String teacher, String classId) throws Exception {
        HttpResponse<String> res = raw(teacher, "GET", "/api/classes", null);
        assertThat(res.statusCode()).isEqualTo(200);
        for (Object o : Json.parseList(res.body())) {
            Map<?, ?> c = (Map<?, ?>) o;
            if (classId.equals(c.get("id"))) {
                return c.get("inviteCode").toString();
            }
        }
        throw new AssertionError("找不到班级 " + classId);
    }

    private void join(String cookie, String inviteCode) throws Exception {
        send(cookie, "POST", "/api/classes/join", Map.of("inviteCode", inviteCode));
    }

    private String createOffering(String teacher, String classId) throws Exception {
        String paperId = post(teacher, "/api/papers",
                Map.of("title", "限流测试卷", "defaultDurationMinutes", 60), "id");
        String sectionId = post(teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "写作", "pointsPerItem", 20), "id");
        post(teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1, "questionType", "ESSAY",
                "stem", "Write an essay.", "points", 20, "paperSectionId", sectionId), "id");
        post(teacher, "/api/papers/" + paperId + "/publish", Map.of(), "id");
        Map<String, Object> body = Map.of(
                "paperId", paperId, "kind", "ASSIGNMENT", "timingMode", "INDIVIDUAL",
                "dueAt", Instant.now().plusSeconds(7 * 86400).toString(), "durationMinutes", 60,
                "classes", List.of(Map.of("classId", classId)));
        String offeringId = sendMap(teacher, "POST", "/api/offerings", body).get("id").toString();
        send(teacher, "POST", "/api/offerings/" + offeringId + "/publish", null);
        return offeringId;
    }

    private String post(String cookie, String path, Object body, String field) throws Exception {
        HttpResponse<String> res = send(cookie, "POST", path, body);
        Map<String, Object> parsed = Json.parse(res.body());
        assertThat(parsed.get(field)).as("%s 返回缺少字段 %s", path, field).isNotNull();
        return parsed.get(field).toString();
    }

    private HttpResponse<String> send(String cookie, String method, String path, Object body)
            throws Exception {
        HttpResponse<String> res = raw(cookie, method, path, body);
        assertThat(res.statusCode()).withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return res;
    }

    /** 与 send 相同，但返回已解析的 body，便于取字段。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sendMap(String cookie, String method, String path, Object body)
            throws Exception {
        return Json.parse(send(cookie, method, path, body).body());
    }

    private HttpResponse<String> upload(String cookie, String offeringId, String fileName) throws Exception {
        byte[] content = ("upload body of " + fileName).getBytes(StandardCharsets.UTF_8);
        String boundary = "----teachloop" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(base + "/api/offerings/" + offeringId + "/submissions/file"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> raw(String cookie, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
