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

/** T030/T032/T036：开发登录、管理员登录、教师邀请码、停用即踢下线。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IdentityAuthTest {

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
    @DisplayName("dev 登录可用；管理员可登录；邀请码兑换一次后作废；停用立即 401")
    void adminInviteAndDisable() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");

        String teacher = login(base, "teacher@teachloop.local", "teachloop123");
        assertThat(teacher).isNotBlank();
        // 开发 profile 关闭 CSRF 时 /api/csrf 也应 200（返回空 token），不能 500
        assertThat(request(base, null, "GET", "/api/csrf", null).statusCode()).isEqualTo(200);

        adminService.createAdmin("root@teachloop.local", "root-pass-123");
        String admin = login(base, "root@teachloop.local", "root-pass-123");
        assertThat(me(base, admin).get("role")).isEqualTo("ADMIN");

        HttpResponse<String> invite = request(base, admin, "POST", "/api/admin/teacher-invites", Map.of());
        assertThat(invite.statusCode()).isEqualTo(200);
        Map<String, Object> inviteBody = Json.parse(invite.body());
        String code = inviteBody.get("code").toString();

        HttpResponse<String> forbidden = request(base, teacher, "POST", "/api/admin/teacher-invites", Map.of());
        assertThat(forbidden.statusCode()).isEqualTo(403);
        HttpResponse<String> student = request(base, login(base, "student@teachloop.local", "teachloop123"),
                "POST", "/api/papers", Map.of("title", "越权"));
        assertThat(student.statusCode()).isEqualTo(403);
        assertThat(request(base, null, "GET", "/api/papers", null).statusCode()).isEqualTo(401);

        // AC-3.3（2026-10-30 改写）：兑换只认会话、只升自己；匿名请求 401（不再能凭码开户）。
        assertThat(request(base, null, "POST", "/api/auth/teacher-invite",
                Map.of("code", code)).statusCode()).isEqualTo(401);

        String studentCookie = login(base, "student@teachloop.local", "teachloop123");
        Map<String, Object> beforeUpgrade = Json.parse(
                request(base, studentCookie, "GET", "/api/features", null).body());
        assertThat(beforeUpgrade)
                .containsEntry("nav.assignments", false)
                .containsEntry("nav.vocab.review", true);

        Map<String, Object> redeem = Json.parse(request(base, studentCookie, "POST",
                "/api/auth/teacher-invite", Map.of("code", code)).body());
        assertThat(redeem.get("role")).isEqualTo("TEACHER");

        // AC-3.12 / AC-3.13：不重新登录，同一个 Cookie 立刻是教师；学习入口不消失。
        assertThat(me(base, studentCookie).get("role")).isEqualTo("TEACHER");
        Map<String, Object> afterUpgrade = Json.parse(
                request(base, studentCookie, "GET", "/api/features", null).body());
        assertThat(afterUpgrade)
                .containsEntry("nav.assignments", true)
                .containsEntry("nav.grading", true)
                .containsEntry("nav.vocab.review", true)
                .containsEntry("nav.vocab.lists", true)
                .containsEntry("nav.vocab.library", true)
                .containsEntry("nav.teachers", false)
                .containsEntry("nav.vocab.books", false);

        // 码一次性：另一个学生再用同一个码 → 404。
        adminService.createUser("second@teachloop.local", UserRole.STUDENT, "第二个学生",
                "second-pass-123", true);
        String secondStudent = login(base, "second@teachloop.local", "second-pass-123");
        assertThat(request(base, secondStudent, "POST", "/api/auth/teacher-invite",
                Map.of("code", code)).statusCode()).isEqualTo(404);

        // 非 STUDENT 再兑换 → 409（教师不会被重复升级，更不会借这条升管理员）。
        assertThat(request(base, studentCookie, "POST", "/api/auth/teacher-invite",
                Map.of("code", "ZZZZZZZZ")).statusCode()).isEqualTo(409);

        String disablePath = "/api/admin/users/" + redeem.get("id") + "/disable";
        assertThat(request(base, admin, "POST", disablePath, Map.of()).statusCode()).isEqualTo(200);
        // 停用后旧会话立即失效：未认证请求返回 401，不会拿到 200。
        assertThat(request(base, studentCookie, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
    }

    private String login(String base, String email, String password) throws Exception {
        return cookieOf(request(base, null, "POST", "/api/auth/login", Map.of("email", email, "password", password)));
    }

    private Map<String, Object> me(String base, String cookie) throws Exception {
        HttpResponse<String> res = request(base, cookie, "GET", "/api/auth/me", null);
        assertThat(res.statusCode()).isEqualTo(200);
        return Json.parse(res.body());
    }

    private String cookieOf(HttpResponse<String> res) {
        assertThat(res.statusCode()).isEqualTo(200);
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
