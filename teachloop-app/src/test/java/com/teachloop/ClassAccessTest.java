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
import java.util.List;
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

/** T042/T043：成员列表（姓名+提交数）与班级访问控制。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ClassAccessTest {

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
    @DisplayName("T042/T043：教师只见自己的班；学生访问成员 403；越权 404；成员含姓名与提交数")
    void classAccessMatrix() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        users.save(new User(UUID.randomUUID(), UserRole.TEACHER, "teacher2@teachloop.local",
                encoder.encode("teachloop123"), "二号老师", Instant.now()));

        String teacher1 = login(base, "teacher@teachloop.local");
        String teacher2 = login(base, "teacher2@teachloop.local");
        String student = login(base, "student@teachloop.local");

        Map<String, Object> classA = Json.parse(request(base, teacher1, "POST", "/api/classes",
                Map.of("name", "A 班")).body());
        Map<String, Object> classB = Json.parse(request(base, teacher2, "POST", "/api/classes",
                Map.of("name", "B 班")).body());

        List<String> mine1 = names(Json.parseList(request(base, teacher1, "GET", "/api/classes", null).body()));
        assertThat(mine1).contains("A 班").doesNotContain("B 班");

        assertThat(request(base, teacher1, "GET", "/api/classes/" + classB.get("id") + "/members", null)
                .statusCode()).isEqualTo(404);
        assertThat(request(base, student, "GET", "/api/classes/" + classA.get("id") + "/members", null)
                .statusCode()).isEqualTo(403);

        request(base, student, "POST", "/api/classes/join", Map.of("inviteCode", classA.get("inviteCode")));
        List<String> studentClasses = names(Json.parseList(request(base, student, "GET", "/api/classes", null).body()));
        assertThat(studentClasses).contains("A 班");
        assertThat(request(base, student, "POST",
                "/api/classes/" + classA.get("id") + "/invite/regenerate", Map.of()).statusCode()).isEqualTo(404);

        HttpResponse<String> memberRes = request(base, teacher1, "GET",
                "/api/classes/" + classA.get("id") + "/members", null);
        assertThat(memberRes.statusCode()).withFailMessage(memberRes.body()).isEqualTo(200);
        List<?> members = Json.parseList(memberRes.body());
        assertThat(members).hasSize(1);
        Map<?, ?> member = (Map<?, ?>) members.get(0);
        assertThat(member.get("displayName")).isEqualTo("示例学生");
        assertThat(((Number) member.get("submissionCount")).intValue()).isZero();
    }

    private static List<String> names(List<?> rows) {
        return rows.stream().map(o -> String.valueOf(((Map<?, ?>) o).get("name"))).toList();
    }

    private String login(String base, String email) throws Exception {
        HttpResponse<String> res = request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
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

        static List<?> parseList(String s) { return MAPPER.readValue(s, List.class); }
    }
}
