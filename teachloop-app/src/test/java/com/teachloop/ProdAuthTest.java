package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import com.teachloop.identity.admin.AdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T030：生产 profile 下开发替身登录必须 503。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "teachloop.grading.engine=ai")
@ActiveProfiles("prod")
@Testcontainers
class ProdAuthTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.seed.enabled", () -> "true");
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
    @DisplayName("T030：prod profile 下教师/学生密码登录返回 503，而不是签发会话")
    void devLoginDisabledInProd() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        Map<String, Object> csrf = csrf(base);
        HttpClient noRedirect = client;
        HttpResponse<String> res = noRedirect.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", csrf.get("cookie").toString())
                        .header("X-XSRF-TOKEN", csrf.get("token").toString())
                        .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"teacher@teachloop.local\",\"password\":\"teachloop123\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(Json.parse(res.body()).get("code")).isEqualTo("UNAVAILABLE");
    }

    @Test
    @DisplayName("T033：已登录写操作缺 CSRF 返回 403；带 token 可通过")
    void csrfDoubleSubmit() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        adminService.createAdmin("csrf-admin@teachloop.local", "csrf-pass-123");
        Map<String, Object> csrf = csrf(base);
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("Cookie", csrf.get("cookie").toString())
                .header("X-XSRF-TOKEN", csrf.get("token").toString())
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"csrf-admin@teachloop.local\",\"password\":\"csrf-pass-123\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String session = login.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();

        HttpResponse<String> missing = client.send(HttpRequest.newBuilder(URI.create(base + "/api/admin/teacher-invites"))
                .header("Cookie", session)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(403);

        HttpResponse<String> withToken = client.send(HttpRequest.newBuilder(URI.create(base + "/api/admin/teacher-invites"))
                .header("Cookie", session + "; " + csrf.get("cookie"))
                .header("X-XSRF-TOKEN", csrf.get("token").toString())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(withToken.statusCode()).isEqualTo(200);
    }

    private Map<String, Object> csrf(String base) throws Exception {
        HttpResponse<String> tokenRes = client.send(HttpRequest.newBuilder(URI.create(base + "/api/csrf"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(tokenRes.statusCode()).isEqualTo(200);
        Map<String, Object> body = Json.parse(tokenRes.body());
        String cookie = tokenRes.headers().allValues("set-cookie").stream()
                .map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow();
        body.put("cookie", cookie);
        return body;
    }

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) { return MAPPER.readValue(s, Map.class); }
    }
}
