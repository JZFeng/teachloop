package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.admin.AdminService;
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
 * 009 T520：`PENDING`（已注册未激活）账号不能登录、不能沿用已有会话。
 *
 * <p>与 DISABLED 的区别是语义：PENDING 要引导去激活，DISABLED 是管理员停用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PendingAccountTest {

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
    @Autowired UserRepository users;
    @Autowired AdminService adminService;
    @Autowired PasswordEncoder encoder;

    private final HttpClient client = HttpClient.newHttpClient();
    private final Clock clock = Clock.systemUTC();

    @Test
    @DisplayName("PENDING 账号密码登录返回 403 + ACCOUNT_PENDING，不是 401")
    void pendingCannotLogin() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        users.save(new User(UUID.randomUUID(), UserRole.STUDENT, "pending@teachloop.local",
                encoder.encode("pending-pass-123"), "未激活", Instant.now(clock)));
        User pending = users.findByEmail("pending@teachloop.local").orElseThrow();
        pending.setStatus(UserStatus.PENDING);
        users.save(pending);

        HttpResponse<String> res = post(base, "/api/auth/login",
                "{\"email\":\"pending@teachloop.local\",\"password\":\"pending-pass-123\"}");

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("ACCOUNT_PENDING");
    }

    @Test
    @DisplayName("已登录的会话在账号被置为 PENDING 后立即失效（401）")
    void pendingKillsExistingSession() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        adminService.createUser("active-then-pending@teachloop.local", UserRole.STUDENT,
                "先激活后挂起", "active-pass-123", true);
        HttpResponse<String> login = post(base, "/api/auth/login",
                "{\"email\":\"active-then-pending@teachloop.local\",\"password\":\"active-pass-123\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        String cookie = login.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(get(base, "/api/auth/me", cookie).statusCode()).isEqualTo(200);

        User user = users.findByEmail("active-then-pending@teachloop.local").orElseThrow();
        user.setStatus(UserStatus.PENDING);
        users.save(user);

        assertThat(get(base, "/api/auth/me", cookie).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> post(String base, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String base, String path, String cookie) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
