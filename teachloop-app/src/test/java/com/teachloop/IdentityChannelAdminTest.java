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

/** 009 T534：通道配置仅 ADMIN 可见；密钥只回掩码；保存后下一次请求即生效。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IdentityChannelAdminTest {

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

    @Autowired Environment env;
    @Autowired AdminService adminService;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("非管理员访问配置接口 403；管理员可读")
    void onlyAdmin() throws Exception {
        String base = base();
        adminService.createUser("chan-teacher@test.local", UserRole.TEACHER, "老师", "password123", true);
        String teacher = login(base, "chan-teacher@test.local", "password123");

        assertThat(get(base, "/api/admin/identity-channels", teacher).statusCode()).isEqualTo(403);

        adminService.createAdmin("chan-admin@test.local", "adminpass123");
        String admin = login(base, "chan-admin@test.local", "adminpass123");
        HttpResponse<String> res = get(base, "/api/admin/identity-channels", admin);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("email").contains("sms").contains("wechat");
    }

    @Test
    @DisplayName("保存发件人与授权码后：登录页通道立刻出现，响应里永远没有明文密钥")
    void saveMakesChannelVisibleWithoutLeakingSecret() throws Exception {
        String base = base();
        adminService.createAdmin("chan-admin2@test.local", "adminpass123");
        String admin = login(base, "chan-admin2@test.local", "adminpass123");

        String body = """
                {"enabled":true,"driver":"smtp","fromAddress":"no-reply@test.local",
                 "baseUrl":"smtp.test.local:465","secret":"super-secret-code","dailyQuota":200}
                """;
        HttpResponse<String> put = put(base, "/api/admin/identity-channels/email", admin, body);
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(put.body()).doesNotContain("super-secret-code");
        assertThat(put.body()).contains("\"hasSecret\":true");
        assertThat(put.body()).contains("secretUnreadable\":false");

        // 公开的通道发现端点立刻反映出来（无缓存）
        assertThat(get(base, "/api/auth/channels", null).body()).contains("email");

        // 再读一次管理端，仍然只有掩码
        HttpResponse<String> again = get(base, "/api/admin/identity-channels", admin);
        assertThat(again.body()).doesNotContain("super-secret-code");
        assertThat(again.body()).contains("hasSecret\":true");
    }

    @Test
    @DisplayName("关掉通道后，登录页不再出现该通道")
    void disableHidesChannel() throws Exception {
        String base = base();
        adminService.createAdmin("chan-admin3@test.local", "adminpass123");
        String admin = login(base, "chan-admin3@test.local", "adminpass123");

        put(base, "/api/admin/identity-channels/email", admin,
                "{\"enabled\":true,\"driver\":\"smtp\",\"fromAddress\":\"a@b.c\",\"baseUrl\":\"h:465\",\"secret\":\"x\"}");
        assertThat(get(base, "/api/auth/channels", null).body()).contains("email");

        put(base, "/api/admin/identity-channels/email", admin,
                "{\"enabled\":false,\"driver\":\"smtp\",\"fromAddress\":\"a@b.c\",\"baseUrl\":\"h:465\"}");
        assertThat(get(base, "/api/auth/channels", null).body()).doesNotContain("email");
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private String login(String base, String email, String password) throws Exception {
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        return res.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
    }

    private HttpResponse<String> get(String base, String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (cookie != null) b.header("Cookie", cookie);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String base, String path, String cookie, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    static final class Ignored {
        private Ignored() { }
        static Map<String, Object> empty() { return Map.of(); }
    }
}
