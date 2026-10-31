package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.admin.AdminService;
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
 * 007-role-nav / T479 验收：方法级收口。
 *
 * <p>两组断言：
 * <ul>
 *   <li>管理员拿不到教师专属端点（403），教师不被误拦（非 403）；</li>
 *   <li>学生答题链路与 /api/features 的 key 集合都符合矩阵。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RoleAccessTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ADMIN_EMAIL = "role-admin@teachloop.local";
    private static final String ADMIN_PASS = "role-admin-pass-123";

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
    @DisplayName("管理员对教师专属端点 403；同一端点上教师不是 403")
    void adminIsDeniedTeacherEndpoints() throws Exception {
        String base = base();
        String admin = adminCookie(base);
        String teacher = login(base, "teacher@teachloop.local", "teachloop123");

        String offeringId = UUID.randomUUID().toString();
        List<String[]> teacherOnly = List.of(
                new String[]{"GET", "/api/papers"},
                new String[]{"GET", "/api/grading/queue"},
                new String[]{"POST", "/api/offerings"},
                new String[]{"GET", "/api/offerings/" + offeringId + "/class-summary"},
                new String[]{"GET", "/api/offerings/" + offeringId + "/submissions"},
                new String[]{"POST", "/api/offerings/" + offeringId + "/publish"},
                new String[]{"POST", "/api/offerings/" + offeringId + "/grading/dispatch"},
                new String[]{"POST", "/api/offerings/" + offeringId + "/submissions/" + UUID.randomUUID() + "/reject"},
                new String[]{"GET", "/api/reports/wrong-items?paperId=" + UUID.randomUUID()}
        );

        for (String[] ep : teacherOnly) {
            Object body = "POST".equals(ep[0]) ? Map.of() : null;
            assertThat(request(base, admin, ep[0], ep[1], body).statusCode())
                    .as("admin %s %s 应被拒", ep[0], ep[1])
                    .isEqualTo(403);
            assertThat(request(base, teacher, ep[0], ep[1], body).statusCode())
                    .as("teacher %s %s 不应被 403", ep[0], ep[1])
                    .isNotEqualTo(403);
        }
    }

    @Test
    @DisplayName("学生答题链路不被收口影响")
    void studentExamChainIsNotBlocked() throws Exception {
        String base = base();
        String student = login(base, "student@teachloop.local", "teachloop123");

        assertThat(request(base, student, "GET", "/api/offerings", null).statusCode()).isEqualTo(200);
        // 创建布置是教师动作，学生必须被拒
        assertThat(request(base, student, "POST", "/api/offerings", Map.of()).statusCode()).isEqualTo(403);
        // 教师才能看某份布置的提交列表
        assertThat(request(base, student, "GET",
                "/api/offerings/" + UUID.randomUUID() + "/submissions", null).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("/api/features 按角色返回 key 集合")
    void featuresReflectRoleMatrix() throws Exception {
        String base = base();
        String admin = adminCookie(base);
        String student = login(base, "student@teachloop.local", "teachloop123");

        Map<String, Object> adminFeatures = Json.parse(
                request(base, admin, "GET", "/api/features", null).body());
        assertThat(adminFeatures)
                .containsEntry("nav.classes", false)
                .containsEntry("nav.ai", true)
                .containsEntry("nav.teachers", true)
                .containsEntry("nav.home", false)
                .containsEntry("nav.reports", false)
                // AC-3.14：管理员没有任何学习入口（词书维护不算学习入口）
                .containsEntry("nav.vocab.review", false);

        Map<String, Object> studentFeatures = Json.parse(
                request(base, student, "GET", "/api/features", null).body());
        assertThat(studentFeatures)
                .doesNotContainKey("nav.feedback")
                .containsEntry("nav.papers", false)
                .containsEntry("nav.grading", false)
                .containsEntry("nav.teachers", false);
    }

    @Test
    @DisplayName("管理员对班级接口 403（2026-10-27：班级只属于师生）")
    void adminIsDeniedClassEndpoints() throws Exception {
        String base = base();
        String admin = adminCookie(base);
        String teacher = login(base, "teacher@teachloop.local", "teachloop123");

        assertThat(request(base, admin, "GET", "/api/classes", null).statusCode()).isEqualTo(403);
        assertThat(request(base, admin, "POST", "/api/classes", Map.of("name", "管理员不该建的班"))
                .statusCode()).isEqualTo(403);
        // 管理员也不能借 requireOwned 的旧例外去看别人的班或轮换邀请码。
        assertThat(request(base, admin, "GET", "/api/classes/" + UUID.randomUUID() + "/members", null)
                .statusCode()).isEqualTo(403);
        assertThat(request(base, admin, "POST", "/api/classes/" + UUID.randomUUID() + "/invite/regenerate",
                Map.of()).statusCode()).isEqualTo(403);
        // 同一批端点对教师仍放行。
        assertThat(request(base, teacher, "GET", "/api/classes", null).statusCode()).isEqualTo(200);
    }

    // ---------- helpers ----------

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private String adminCookie(String base) throws Exception {
        try {
            adminService.createAdmin(ADMIN_EMAIL, ADMIN_PASS);
        } catch (RuntimeException ignored) {
            // 同一上下文里重复创建会失败，说明已存在，直接登录即可。
        }
        return login(base, ADMIN_EMAIL, ADMIN_PASS);
    }

    private String login(String base, String email, String password) throws Exception {
        return cookieOf(request(base, null, "POST", "/api/auth/login", Map.of("email", email, "password", password)));
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
