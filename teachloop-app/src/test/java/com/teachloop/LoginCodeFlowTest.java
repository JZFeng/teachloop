package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.admin.AdminService;
import com.teachloop.identity.ai.SecretBox;
import com.teachloop.identity.auth.channel.SmtpMailSender;
import com.teachloop.identity.auth.channel.SmtpMailSenderFactory;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.identity.domain.UserStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 009 T531 / T533：验证码登录与忘记密码。
 *
 * <p>邮件用记录型假实现，验证码从「邮件正文」里取——保证正文格式与解析一起被覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LoginCodeFlowTest.MailConfig.class)
@Testcontainers
class LoginCodeFlowTest {

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

    @TestConfiguration
    static class MailConfig {
        @Bean
        CapturingMail2 capturingMail2() {
            return new CapturingMail2();
        }

        @Bean
        @Primary
        SmtpMailSenderFactory capturingSender2(CapturingMail2 capturing) {
            return capturing;
        }
    }

    static class CapturingMail2 implements SmtpMailSenderFactory {
        final List<String> bodies = new ArrayList<>();

        @Override
        public void send(SmtpMailSender.Settings settings, String to, String subject, String body) {
            bodies.add(body);
        }

        String lastCode() {
            Matcher m = Pattern.compile("(\\d{6})").matcher(bodies.get(bodies.size() - 1));
            return m.find() ? m.group(1) : "";
        }
    }

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecretBox secretBox;
    @Autowired UserRepository users;
    @Autowired AdminService adminService;
    @Autowired PasswordEncoder encoder;
    @Autowired CapturingMail2 mail;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void enableEmail() {
        jdbc.update("""
                update identity_channels
                   set enabled = true, driver = 'smtp', from_address = 'no-reply@test.local',
                       base_url = 'smtp.test.local:465', secret_cipher = ?
                 where channel = 'email'
                """, secretBox.encrypt("test-secret"));
        mail.bodies.clear();
        jdbc.update("delete from verification_codes");
    }

    @Test
    @DisplayName("验证码登录：发码 → 用邮件里的码登录成功；错码 400")
    void codeLogin() throws Exception {
        String base = base();
        adminService.createUser("codelogin@test.local", UserRole.STUDENT, "验证码登录", "password123", true);

        assertThat(post(base, "/api/auth/login/challenge",
                "{\"channel\":\"email\",\"email\":\"codelogin@test.local\"}").statusCode()).isEqualTo(200);
        String code = mail.lastCode();
        assertThat(code).hasSize(6);

        assertThat(post(base, "/api/auth/login/verify",
                "{\"channel\":\"email\",\"email\":\"codelogin@test.local\",\"code\":\"000000\"}").statusCode())
                .isEqualTo(400);

        assertThat(post(base, "/api/auth/login/verify",
                "{\"channel\":\"email\",\"email\":\"codelogin@test.local\",\"code\":\"" + code + "\"}").statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("不认识邮箱 404；未激活账号 403 ACCOUNT_PENDING")
    void challengeGuards() throws Exception {
        String base = base();
        assertThat(post(base, "/api/auth/login/challenge",
                "{\"channel\":\"email\",\"email\":\"nobody@test.local\"}").statusCode()).isEqualTo(404);

        users.save(pendingUser("waiting@test.local"));
        var res = post(base, "/api/auth/login/challenge",
                "{\"channel\":\"email\",\"email\":\"waiting@test.local\"}");
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("ACCOUNT_PENDING");
    }

    @Test
    @DisplayName("同一邮箱 60 秒内二次发码 → 429 且带 Retry-After")
    void cooldown() throws Exception {
        String base = base();
        adminService.createUser("cool@test.local", UserRole.STUDENT, "冷却", "password123", true);
        assertThat(post(base, "/api/auth/login/challenge",
                "{\"channel\":\"email\",\"email\":\"cool@test.local\"}").statusCode()).isEqualTo(200);
        HttpResponse<String> again = post(base, "/api/auth/login/challenge",
                "{\"channel\":\"email\",\"email\":\"cool@test.local\"}");
        assertThat(again.statusCode()).isEqualTo(429);
        assertThat(again.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    @DisplayName("忘记密码：重置后旧密码 401、新密码可登录、旧会话失效")
    void passwordReset() throws Exception {
        String base = base();
        adminService.createUser("reset@test.local", UserRole.STUDENT, "重置", "oldpassword1", true);
        // 先登录，拿一个「旧会话」
        HttpResponse<String> login = post(base, "/api/auth/login",
                "{\"email\":\"reset@test.local\",\"password\":\"oldpassword1\"}");
        String cookie = login.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(get(base, "/api/auth/me", cookie).statusCode()).isEqualTo(200);

        assertThat(post(base, "/api/auth/password-reset/challenge",
                "{\"channel\":\"email\",\"email\":\"reset@test.local\"}").statusCode()).isEqualTo(200);
        String code = mail.lastCode();

        assertThat(post(base, "/api/auth/password-reset/verify",
                "{\"email\":\"reset@test.local\",\"code\":\"" + code + "\",\"newPassword\":\"newpassword1\"}")
                .statusCode()).isEqualTo(200);

        assertThat(post(base, "/api/auth/login",
                "{\"email\":\"reset@test.local\",\"password\":\"oldpassword1\"}").statusCode()).isEqualTo(401);
        assertThat(post(base, "/api/auth/login",
                "{\"email\":\"reset@test.local\",\"password\":\"newpassword1\"}").statusCode()).isEqualTo(200);
        assertThat(get(base, "/api/auth/me", cookie).statusCode()).isEqualTo(401);
    }

    private User pendingUser(String email) {
        User u = new User(UUID.randomUUID(), UserRole.STUDENT, email, encoder.encode("password123"),
                "未激活", Instant.now(Clock.systemUTC()));
        u.setStatus(UserStatus.PENDING);
        return u;
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private HttpResponse<String> post(String base, String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String base, String path, String cookie) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
