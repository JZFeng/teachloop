package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.admin.AdminService;
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

/** T034：未补全手机号/邮箱时除补全页与登出外一律 403；管理员豁免。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "teachloop.security.require-profile-completion=true")
@Testcontainers
class ProfileCompletionTest {

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
    @DisplayName("教师未补全资料时业务接口 403；补全后放行；管理员豁免")
    void requiresProfileCompletion() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local", "teachloop123");

        HttpResponse<String> blocked = request(base, teacher, "GET", "/api/papers", null);
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(Json.parse(blocked.body()).get("code")).isEqualTo("PROFILE_INCOMPLETE");

        assertThat(request(base, teacher, "GET", "/api/profile", null).statusCode()).isEqualTo(200);
        HttpResponse<String> updated = request(base, teacher, "PUT", "/api/profile",
                Map.of("phone", "13800000000", "email", "teacher@teachloop.local", "displayName", "示例老师"));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(request(base, teacher, "GET", "/api/papers", null).statusCode()).isEqualTo(200);

        adminService.createAdmin("admin2@teachloop.local", "admin-pass-123");
        String admin = login(base, "admin2@teachloop.local", "admin-pass-123");
        assertThat(request(base, admin, "POST", "/api/admin/teacher-invites", Map.of()).statusCode()).isEqualTo(200);
    }

    private String login(String base, String email, String password) throws Exception {
        HttpResponse<String> res = request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", password));
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
