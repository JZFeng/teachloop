package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.admin.AdminService;
import com.teachloop.identity.domain.UserRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * 2026-10-27：自助改密码 + 管理员重置。
 *
 * <p>覆盖用户 2026-10-27 选的两条：改密码后**踢掉其他会话**、管理员可在服务器上重置（`--set-password`）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PasswordChangeTest {

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
    AdminService adminService;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("改密码：旧密码错 400；新密码过短 400；成功 200；旧密码登录失败、新密码可登录")
    void changePassword() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        adminService.createUser("pw1@teachloop.local", UserRole.STUDENT, "改密测试", "old-pass-123", true);
        String session = login(base, "pw1@teachloop.local", "old-pass-123");

        // 旧密码错 → 400
        assertThat(post(base, session, "/api/profile/password",
                Map.of("oldPassword", "wrong-pass", "newPassword", "new-pass-123")).statusCode())
                .isEqualTo(400);
        // 新密码过短 → 400
        assertThat(post(base, session, "/api/profile/password",
                Map.of("oldPassword", "old-pass-123", "newPassword", "short")).statusCode())
                .isEqualTo(400);

        // 正确 → 200
        assertThat(post(base, session, "/api/profile/password",
                Map.of("oldPassword", "old-pass-123", "newPassword", "new-pass-123")).statusCode())
                .isEqualTo(200);

        // 旧密码不能再登录；新密码可以
        assertThat(loginStatus(base, "pw1@teachloop.local", "old-pass-123")).isEqualTo(401);
        String fresh = login(base, "pw1@teachloop.local", "new-pass-123");
        assertThat(me(base, fresh).get("email")).isEqualTo("pw1@teachloop.local");
    }

    @Test
    @DisplayName("改密码后，该账号其他会话立即失效（401），当前会话保持登录")
    void changePasswordSignsOutOtherSessions() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        adminService.createUser("pw2@teachloop.local", UserRole.STUDENT, "多端测试", "old-pass-123", true);
        String deviceA = login(base, "pw2@teachloop.local", "old-pass-123");
        String deviceB = login(base, "pw2@teachloop.local", "old-pass-123");
        assertThat(me(base, deviceA).get("email")).isEqualTo("pw2@teachloop.local");

        assertThat(post(base, deviceA, "/api/profile/password",
                Map.of("oldPassword", "old-pass-123", "newPassword", "new-pass-123")).statusCode())
                .isEqualTo(200);

        // A（改密码的那台）继续可用；B 被踢下线。
        assertThat(me(base, deviceA).get("email")).isEqualTo("pw2@teachloop.local");
        assertThat(getStatus(base, deviceB, "/api/auth/me")).isEqualTo(401);
    }

    @Test
    @DisplayName("管理员重置密码：新密码可登录，旧会话失效")
    void adminResetPassword() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        adminService.createUser("pw3@teachloop.local", UserRole.STUDENT, "重置测试", "old-pass-123", true);
        String session = login(base, "pw3@teachloop.local", "old-pass-123");

        adminService.setPassword("pw3@teachloop.local", "reset-pass-123");

        assertThat(loginStatus(base, "pw3@teachloop.local", "old-pass-123")).isEqualTo(401);
        String fresh = login(base, "pw3@teachloop.local", "reset-pass-123");
        assertThat(me(base, fresh).get("email")).isEqualTo("pw3@teachloop.local");
        assertThat(getStatus(base, session, "/api/auth/me")).isEqualTo(401);
    }

    private String login(String base, String email, String password) throws Exception {
        HttpResponse<String> res = post(base, null, "/api/auth/login",
                Map.of("email", email, "password", password));
        assertThat(res.statusCode()).isEqualTo(200);
        return res.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
    }

    private int loginStatus(String base, String email, String password) throws Exception {
        return post(base, null, "/api/auth/login", Map.of("email", email, "password", password)).statusCode();
    }

    private Map<String, Object> me(String base, String cookie) throws Exception {
        HttpResponse<String> res = get(base, cookie, "/api/auth/me");
        assertThat(res.statusCode()).isEqualTo(200);
        return Json.parse(res.body());
    }

    private int getStatus(String base, String cookie, String path) throws Exception {
        return get(base, cookie, path).statusCode();
    }

    private HttpResponse<String> get(String base, String cookie, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (cookie != null) b.header("Cookie", cookie);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String base, String cookie, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (cookie != null) b.header("Cookie", cookie);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static String write(Object o) { return MAPPER.writeValueAsString(o); }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) { return MAPPER.readValue(s, Map.class); }
    }
}
