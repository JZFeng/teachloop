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

    @Test
    @DisplayName("2026-10-28 生产事故：发件人不是邮箱 / 缺授权码时不许启用，注册入口不出现")
    void rejectUnusableSmtpConfig() throws Exception {
        String base = base();
        adminService.createAdmin("chan-admin4@test.local", "adminpass123");
        String admin = login(base, "chan-admin4@test.local", "adminpass123");

        // 先清空授权码（生产当时也只剩「发件人 + 地址」这一类半配置）
        assertThat(put(base, "/api/admin/identity-channels/email", admin,
                "{\"enabled\":false,\"driver\":\"smtp\",\"fromAddress\":\"a@163.com\","
                        + "\"baseUrl\":\"smtp.163.com:465\",\"secret\":\"\"}").statusCode()).isEqualTo(200);

        // 没有授权码就启用 → 400，而且不能出现注册入口
        HttpResponse<String> noSecret = put(base, "/api/admin/identity-channels/email", admin,
                "{\"enabled\":true,\"driver\":\"smtp\",\"fromAddress\":\"a@163.com\","
                        + "\"baseUrl\":\"smtp.163.com:465\"}");
        assertThat(noSecret.statusCode()).isEqualTo(400);
        assertThat(noSecret.body()).containsAnyOf("secretRequired", "auth code", "授权码");
        assertThat(get(base, "/api/auth/channels", null).body()).doesNotContain("email");

        // 配好再启用 → 200（注册入口出现）
        assertThat(put(base, "/api/admin/identity-channels/email", admin,
                "{\"enabled\":true,\"driver\":\"smtp\",\"fromAddress\":\"a@163.com\","
                        + "\"baseUrl\":\"smtp.163.com:465\",\"secret\":\"code\"}").statusCode()).isEqualTo(200);
        assertThat(get(base, "/api/auth/channels", null).body()).contains("email");

        // 发件人写成显示名（生产那次就是这么填的）→ 400，状态保持上一条的可用配置
        HttpResponse<String> badFrom = put(base, "/api/admin/identity-channels/email", admin,
                "{\"enabled\":true,\"driver\":\"smtp\",\"fromAddress\":\"teachloop admin\","
                        + "\"baseUrl\":\"smtp.163.com:465\",\"secret\":\"code\"}");
        assertThat(badFrom.statusCode()).isEqualTo(400);
        assertThat(badFrom.body()).containsAnyOf("fromFormat", "sender must be an email", "发件人");

        // 收尾：关掉通道，别影响同类里其它用例
        put(base, "/api/admin/identity-channels/email", admin,
                "{\"enabled\":false,\"driver\":\"smtp\",\"fromAddress\":\"a@163.com\","
                        + "\"baseUrl\":\"smtp.163.com:465\"}");
        assertThat(get(base, "/api/auth/channels", null).body()).doesNotContain("email");
    }

    @Test
    @DisplayName("邮件 extra（发件人名称 / 认证账号 / 加密方式）保存后按对象回读")
    void emailExtraRoundTrips() throws Exception {
        String base = base();
        adminService.createAdmin("chan-admin5@test.local", "adminpass123");
        String admin = login(base, "chan-admin5@test.local", "adminpass123");

        String body = """
                {"enabled":false,"driver":"smtp","fromAddress":"a@163.com","baseUrl":"smtp.163.com:465",
                 "secret":"code",
                 "extra":{"name":"系统管理员","user":"a@163.com","encryption":"starttls"}}
                """;
        HttpResponse<String> put = put(base, "/api/admin/identity-channels/email", admin, body);
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(put.body()).contains("\"name\":\"系统管理员\"");
        assertThat(put.body()).contains("\"encryption\":\"starttls\"");

        HttpResponse<String> read = get(base, "/api/admin/identity-channels", admin);
        assertThat(read.body()).contains("\"name\":\"系统管理员\"").contains("\"encryption\":\"starttls\"");

        // 收尾：清掉 extra 与授权码，别把这次配置留给同类里的其它用例
        put(base, "/api/admin/identity-channels/email", admin,
                "{\"enabled\":false,\"driver\":\"smtp\",\"fromAddress\":\"a@163.com\","
                        + "\"baseUrl\":\"smtp.163.com:465\",\"secret\":\"\",\"extra\":null}");
    }

    @Test
    @DisplayName("发送测试邮件：通道没配齐时回 ok=false 与原因，不发起真实连接")
    void testSendReportsMissingConfig() throws Exception {
        String base = base();
        adminService.createAdmin("chan-admin6@test.local", "adminpass123");
        String admin = login(base, "chan-admin6@test.local", "adminpass123");

        // 先把授权码清空，保证 configured()=false——否则这里会真的去连 smtp.163.com
        put(base, "/api/admin/identity-channels/email", admin,
                "{\"enabled\":false,\"driver\":\"smtp\",\"fromAddress\":\"a@163.com\","
                        + "\"baseUrl\":\"smtp.163.com:465\",\"secret\":\"\",\"extra\":null}");

        HttpResponse<String> res = post(base, "/api/admin/identity-channels/email/test", admin, "");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"ok\":false");
        assertThat(res.body()).contains("error.identity.channel.notConfigured");
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

    private HttpResponse<String> post(String base, String path, String cookie, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
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
