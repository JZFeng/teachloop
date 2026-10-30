package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.admin.AdminService;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
 * T561 / US-93：资料补全改为可跳过，但教师写操作仍强制。
 *
 * <p>本类跑在 {@code require-profile-completion=true}（prod / qa 的取值）；开关关闭的一侧见
 * {@link ProfileCompletionSwitchOffTest}——两种取值下行为必须一致，因为过滤器已不再读这个开关。
 *
 * <p>AC-93.4 的 403 用例一律用**自建、没有手机号**的教师账号断言，不依赖 dev seed 的取值。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "teachloop.security.require-profile-completion=true")
@Testcontainers
class ProfileCompletionTest {

    private static final String PASSWORD = "teachloop123";

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
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    AdminService adminService;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("AC-93.1 / AC-93.4：缺手机号的教师能浏览（/api/papers 200），但建班与发词 403 PROFILE_INCOMPLETE")
    void incompleteTeacherCanBrowseButNotWrite() throws Exception {
        String base = base();
        String email = ensureIncompleteTeacher("profile-incomplete@teachloop.local");

        // ① 登录本身不受影响，并且响应直接告诉前端「资料不完整」（AC-93.2 的前端依据）。
        HttpResponse<String> login = request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", PASSWORD));
        assertThat(login.statusCode()).withFailMessage(login.body()).isEqualTo(200);
        String cookie = cookieOf(login);
        assertThat(Json.parse(login.body()).get("profileIncomplete")).isEqualTo(Boolean.TRUE);

        // ② AC-93.1：普通接口 200（T561 之前这里是 403 PROFILE_INCOMPLETE）。
        HttpResponse<String> papers = request(base, cookie, "GET", "/api/papers", null);
        assertThat(papers.statusCode()).withFailMessage("AC-93.1 普通接口必须放行: %s", papers.body())
                .isEqualTo(200);

        HttpResponse<String> me = request(base, cookie, "GET", "/api/auth/me", null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(Json.parse(me.body()).get("profileIncomplete")).isEqualTo(Boolean.TRUE);

        // ③ AC-93.4：两个写操作仍 403，且 code 是前端认的 PROFILE_INCOMPLETE。
        HttpResponse<String> createClass = request(base, cookie, "POST", "/api/classes",
                Map.of("name", "缺资料老师建的班"));
        assertThat(createClass.statusCode()).as("AC-93.4 建班必须 403").isEqualTo(403);
        assertThat(Json.parse(createClass.body()).get("code")).isEqualTo("PROFILE_INCOMPLETE");

        HttpResponse<String> assign = request(base, cookie, "POST", "/api/vocab/assignments", Map.of());
        assertThat(assign.statusCode()).as("AC-93.4 发词必须 403").isEqualTo(403);
        assertThat(Json.parse(assign.body()).get("code")).isEqualTo("PROFILE_INCOMPLETE");

        // ④ 资料保存（PUT /api/profile）回归 + 保存后同一个会话立刻放行。
        HttpResponse<String> saved = request(base, cookie, "PUT", "/api/profile",
                Map.of("phone", "13800000011", "email", email, "displayName", "已补全老师"));
        assertThat(saved.statusCode()).withFailMessage(saved.body()).isEqualTo(200);

        assertThat(Json.parse(request(base, cookie, "GET", "/api/auth/me", null).body())
                .get("profileIncomplete")).isEqualTo(Boolean.FALSE);
        HttpResponse<String> created = request(base, cookie, "POST", "/api/classes", Map.of("name", "补全后建的班"));
        assertThat(created.statusCode()).withFailMessage("补全后建班必须 200: %s", created.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("管理员豁免：没有手机号也 profileIncomplete=false，管理接口照常")
    void adminIsExempt() throws Exception {
        String base = base();
        String email = "profile-admin@teachloop.local";
        if (users.findByEmail(email).isEmpty()) {
            adminService.createAdmin(email, "admin-pass-123");
        }
        String cookie = cookieOf(request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "admin-pass-123")));

        assertThat(Json.parse(request(base, cookie, "GET", "/api/auth/me", null).body())
                .get("profileIncomplete")).isEqualTo(Boolean.FALSE);
        assertThat(request(base, cookie, "POST", "/api/admin/teacher-invites", Map.of()).statusCode())
                .isEqualTo(200);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    /** 自建「只有邮箱、没有手机号」的教师；已存在则直接复用（同一测试类里多次调用不会撞 email 唯一键）。 */
    private String ensureIncompleteTeacher(String email) {
        users.findByEmail(email).orElseGet(() -> users.save(new User(UUID.randomUUID(), UserRole.TEACHER,
                email, encoder.encode(PASSWORD), "缺资料老师", Instant.now())));
        return email;
    }

    private static String cookieOf(HttpResponse<String> res) {
        assertThat(res.statusCode()).withFailMessage("登录失败: %s", res.body()).isEqualTo(200);
        return res.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
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
