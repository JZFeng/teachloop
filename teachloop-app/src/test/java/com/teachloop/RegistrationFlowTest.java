package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.ai.SecretBox;
import com.teachloop.identity.auth.channel.SmtpMailSender;
import com.teachloop.identity.auth.channel.SmtpMailSenderFactory;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 009 T526–T529：注册 → 激活 → 登录全链路。
 *
 * <p>邮件用记录型假实现（{@link CapturingMail}），不碰真实 SMTP；激活令牌从「邮件正文」里取，
 * 这样连「链接长什么样」都被覆盖到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RegistrationFlowTest.MailConfig.class)
@Testcontainers
class RegistrationFlowTest {

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
        r.add("teachloop.app.base-url", () -> "https://test.teachloop.org");
    }

    @TestConfiguration
    static class MailConfig {
        @Bean
        CapturingMail capturingMail() {
            return new CapturingMail();
        }

        @Bean
        @org.springframework.context.annotation.Primary
        SmtpMailSenderFactory capturingSmtpSenderFactory(CapturingMail capturing) {
            return capturing;
        }
    }

    static class CapturingMail implements SmtpMailSenderFactory {
        final List<String> bodies = new ArrayList<>();
        final List<String> subjects = new ArrayList<>();

        @Override
        public void send(SmtpMailSender.Settings settings, String to, String subject, String body) {
            subjects.add(subject);
            bodies.add(body);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SecretBox secretBox;
    @Autowired UserRepository users;
    @Autowired CapturingMail mail;

    private final HttpClient client = HttpClient.newHttpClient();

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.core.env.Environment springEnv;

    @BeforeEach
    void enableEmailChannel() {
        jdbc.update("""
                update identity_channels
                   set enabled = true, driver = 'smtp', from_address = 'no-reply@test.local',
                       base_url = 'smtp.test.local:465', secret_cipher = ?
                 where channel = 'email'
                """, secretBox.encrypt("test-secret"));
        mail.bodies.clear();
        mail.subjects.clear();
    }

    @Test
    @DisplayName("注册 → 收到激活信 → GET 不激活 → POST 激活 → 可登录")
    void registerActivateLogin() throws Exception {
        String base = baseUrl();

        assertThat(get(base, "/api/auth/channels").body()).contains("email");

        var reg = post(base, "/api/auth/register",
                "{\"channel\":\"email\",\"email\":\"newbie@test.local\",\"password\":\"password123\",\"displayName\":\"新同学\"}");
        assertThat(reg.statusCode()).isEqualTo(200);
        assertThat(users.findByEmail("newbie@test.local").orElseThrow().getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(mail.bodies).hasSize(1);

        String token = tokenFrom(mail.bodies.get(0));
        assertThat(token).isNotBlank();

        // GET 只读：账号仍是 PENDING
        assertThat(get(base, "/api/auth/activate?token=" + token).body()).contains("OK");
        assertThat(users.findByEmail("newbie@test.local").orElseThrow().getStatus()).isEqualTo(UserStatus.PENDING);

        // 未激活登录 403
        assertThat(post(base, "/api/auth/login",
                "{\"email\":\"newbie@test.local\",\"password\":\"password123\"}").statusCode()).isEqualTo(403);

        // POST 激活
        assertThat(post(base, "/api/auth/activate", "{\"token\":\"" + token + "\"}").statusCode()).isEqualTo(200);
        assertThat(users.findByEmail("newbie@test.local").orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);

        // 现在能登录
        assertThat(post(base, "/api/auth/login",
                "{\"email\":\"newbie@test.local\",\"password\":\"password123\"}").statusCode()).isEqualTo(200);

        // 已激活再注册 → 409
        assertThat(post(base, "/api/auth/register",
                "{\"channel\":\"email\",\"email\":\"newbie@test.local\",\"password\":\"password123\"}").statusCode())
                .isEqualTo(409);

        // 令牌复用 → USED
        assertThat(get(base, "/api/auth/activate?token=" + token).body()).contains("USED");
    }

    @Test
    @DisplayName("PENDING 账号重复注册 → 重发激活信，不建第二个账号")
    void resendForPending() throws Exception {
        String base = baseUrl();
        assertThat(post(base, "/api/auth/register",
                "{\"channel\":\"email\",\"email\":\"dup@test.local\",\"password\":\"password123\"}").statusCode())
                .isEqualTo(200);
        long countAfterFirst = users.findByEmail("dup@test.local").stream().count();
        assertThat(countAfterFirst).isEqualTo(1L);

        var again = post(base, "/api/auth/register",
                "{\"channel\":\"email\",\"email\":\"dup@test.local\",\"password\":\"password123\"}");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.body()).contains("resent");
        assertThat(mail.bodies).hasSize(2);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + springEnv.getProperty("local.server.port");
    }

    private String tokenFrom(String body) {
        Matcher m = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    private HttpResponse<String> post(String base, String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
