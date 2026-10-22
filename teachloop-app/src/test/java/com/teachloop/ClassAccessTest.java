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
        // 同一个 Spring 上下文 / 数据库里可能已有别的测试方法建过 teacher2（顺序不定），存在就复用。
        if (users.findByEmail("teacher2@teachloop.local").isEmpty()) {
            users.save(new User(UUID.randomUUID(), UserRole.TEACHER, "teacher2@teachloop.local",
                    encoder.encode("teachloop123"), "二号老师", Instant.now()));
        }

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

    @Test
    @DisplayName("教师之间布置互不可见：列表只有自己布置的，别人的布置详情 404")
    void offeringsScopedToOwningTeacher() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        // 同一个 Spring 上下文 / 数据库里可能已有别的测试方法建过 teacher2，存在就复用，避免 email 冲突。
        if (users.findByEmail("teacher2@teachloop.local").isEmpty()) {
            users.save(new User(UUID.randomUUID(), UserRole.TEACHER, "teacher2@teachloop.local",
                    encoder.encode("teachloop123"), "二号老师", Instant.now()));
        }

        String teacher1 = login(base, "teacher@teachloop.local");
        String teacher2 = login(base, "teacher2@teachloop.local");

        // 每个测试方法自己建班：不依赖另一个方法的执行顺序。
        String classAId = String.valueOf(Json.parse(request(base, teacher1, "POST", "/api/classes",
                Map.of("name", "归属校验 A 班")).body()).get("id"));
        String classBId = String.valueOf(Json.parse(request(base, teacher2, "POST", "/api/classes",
                Map.of("name", "归属校验 B 班")).body()).get("id"));
        assertThat(classAId).isNotEqualTo(classBId);

        // teacher2 在 classB 建一份独立作业（总分 10 = 两项各 5 分）并直接发布。
        HttpResponse<String> created = request(base, teacher2, "POST", "/api/offerings", Map.of(
                "title", "二号老师的独立作业",
                "instructions", "用两段话介绍你最喜欢的季节，并说明理由。",
                "totalPoints", 10,
                "rubricMarkdown", "# Scoring Rubric\n\n"
                        + "### 1. Content — 5 points\n是否切题且给出理由。\n- 是：5 分\n- 否：0 分\n\n"
                        + "### 2. Language — 5 points\n语法与用词是否正确。\n- 是：5 分\n- 否：0 分\n",
                "dueAt", "2027-12-31T23:59:59Z",
                "timingMode", "INDIVIDUAL",
                "classes", List.of(Map.of("classId", classBId)),
                "publish", true));
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(200);
        Map<String, Object> offering = Json.parse(created.body());
        String offeringId = String.valueOf(offering.get("id"));
        assertThat(offering.get("status")).as("publish=true 必须落成 PUBLISHED").isEqualTo("PUBLISHED");

        // ① 列表：teacher1 看不到，teacher2 看得到（这就是前端「最近作业」串到别的老师数据的那段）。
        List<String> teacher1Ids = offeringIds(Json.parseList(
                request(base, teacher1, "GET", "/api/offerings", null).body()));
        List<String> teacher2Ids = offeringIds(Json.parseList(
                request(base, teacher2, "GET", "/api/offerings", null).body()));
        assertThat(teacher1Ids).as("教师不能看到别的老师布置的作业").doesNotContain(offeringId);
        assertThat(teacher2Ids).as("布置人对自己的作业必须可见").contains(offeringId);

        // ② 详情：越权一律 404（不是 403，避免泄漏「这个布置存在」）。
        assertThat(request(base, teacher1, "GET", "/api/offerings/" + offeringId, null).statusCode())
                .isEqualTo(404);
        assertThat(request(base, teacher2, "GET", "/api/offerings/" + offeringId, null).statusCode())
                .isEqualTo(200);
    }

    private static List<String> names(List<?> rows) {
        return rows.stream().map(o -> String.valueOf(((Map<?, ?>) o).get("name"))).toList();
    }

    /** 布置列表里每一行的 id（列表项字段名是 id）。 */
    private static List<String> offeringIds(List<?> rows) {
        return rows.stream().map(o -> String.valueOf(((Map<?, ?>) o).get("id"))).toList();
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
