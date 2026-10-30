package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

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
 * T561：{@code require-profile-completion=false}（dev 的取值）下行为必须与 true 一侧一致。
 *
 * <p>开关在 T561 之后不再影响放行结果（过滤器只写请求属性，端点自己判），
 * 所以这里断言同样的三件事：普通接口 200、{@code me.profileIncomplete=true}、教师建班仍 403。
 * 若哪天有人把开关重新引回放行逻辑，本类会先红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "teachloop.security.require-profile-completion=false")
@Testcontainers
class ProfileCompletionSwitchOffTest {

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

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("开关关闭时：过滤状态照常计算（me.profileIncomplete=true），建班仍 403 PROFILE_INCOMPLETE")
    void switchOffBehavesLikeSwitchOn() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String email = "profile-switchoff@teachloop.local";
        users.findByEmail(email).orElseGet(() -> users.save(new User(UUID.randomUUID(), UserRole.TEACHER,
                email, encoder.encode(PASSWORD), "开关关闭老师", Instant.now())));

        HttpResponse<String> login = request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", PASSWORD));
        assertThat(login.statusCode()).withFailMessage(login.body()).isEqualTo(200);
        String cookie = login.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();

        assertThat(Json.parse(request(base, cookie, "GET", "/api/auth/me", null).body())
                .get("profileIncomplete")).as("开关关闭时 /me.profileIncomplete 也必须正确")
                .isEqualTo(Boolean.TRUE);
        assertThat(request(base, cookie, "GET", "/api/papers", null).statusCode())
                .as("开关关闭时普通接口同样 200").isEqualTo(200);

        HttpResponse<String> createClass = request(base, cookie, "POST", "/api/classes", Map.of("name", "开关关闭建的班"));
        assertThat(createClass.statusCode()).as("AC-93.4 不受开关影响").isEqualTo(403);
        assertThat(Json.parse(createClass.body()).get("code")).isEqualTo("PROFILE_INCOMPLETE");
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
