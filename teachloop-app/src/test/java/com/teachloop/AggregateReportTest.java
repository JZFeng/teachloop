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

/** T150：按作业/班级/学生聚合；平均分只算已提交，未交单列。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AggregateReportTest {

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
    @DisplayName("T150：2 人班级 1 人提交，作业/班级/学生三视角未交与均分一致")
    void aggregatesByOfferingClassAndStudent() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        users.save(new User(UUID.randomUUID(), UserRole.STUDENT, "student2@teachloop.local",
                encoder.encode("teachloop123"), "二号学生", Instant.now()));

        String teacher = login(base, "teacher@teachloop.local");
        String student1 = login(base, "student@teachloop.local");
        String student2 = login(base, "student2@teachloop.local");

        String paperId = post(base, teacher, "/api/papers", Map.of("title", "聚合卷"), "id");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "单选", "pointsPerItem", 10), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "SINGLE_CHOICE", "stem", "Q1", "answerKey", "A", "points", 10,
                "paperSectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/publish", Map.of(), "id");

        String classId = post(base, teacher, "/api/classes", Map.of("name", "聚合班"), "id");
        List<?> classRows = Json.parseList(request(base, teacher, "GET", "/api/classes", null).body());
        String invite = ((Map<?, ?>) classRows.get(0)).get("inviteCode").toString();
        request(base, student1, "POST", "/api/classes/join", Map.of("inviteCode", invite));
        request(base, student2, "POST", "/api/classes/join", Map.of("inviteCode", invite));

        String offeringId = post(base, teacher, "/api/offerings", Map.of("paperId", paperId, "kind", "EXAM",
                "timingMode", "INDIVIDUAL", "dueAt", "2026-12-31T23:59:59Z", "durationMinutes", 60,
                "classes", List.of(Map.of("classId", classId))), "id");
        post(base, teacher, "/api/offerings/" + offeringId + "/publish", Map.of(), "id");
        request(base, student1, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        request(base, student1, "POST", "/api/offerings/" + offeringId + "/submissions",
                Map.of("answers", List.of(Map.of("number", 1, "answer", "A"))));

        List<?> offerings = Json.parseList(request(base, teacher, "GET",
                "/api/reports/offerings?offeringIds=" + offeringId, null).body());
        Map<?, ?> offering = (Map<?, ?>) offerings.get(0);
        assertThat(((Number) offering.get("expected")).longValue()).isEqualTo(2);
        assertThat(((Number) offering.get("submitted")).longValue()).isEqualTo(1);
        assertThat(((Number) offering.get("unsubmitted")).longValue()).isEqualTo(1);
        assertThat(((Number) offering.get("averageScore")).doubleValue()).isEqualTo(10.0);

        List<?> classes = Json.parseList(request(base, teacher, "GET",
                "/api/reports/classes?classIds=" + classId, null).body());
        Map<?, ?> clazz = (Map<?, ?>) classes.get(0);
        assertThat(((Number) clazz.get("expected")).longValue()).isEqualTo(2);
        assertThat(((Number) clazz.get("submitted")).longValue()).isEqualTo(1);
        assertThat(((Number) clazz.get("unsubmitted")).longValue()).isEqualTo(1);

        List<?> students = Json.parseList(request(base, teacher, "GET",
                "/api/reports/students?offeringIds=" + offeringId, null).body());
        assertThat(students).hasSize(2);
        long submittedRows = students.stream()
                .filter(o -> ((Number) ((Map<?, ?>) o).get("submitted")).longValue() == 1).count();
        assertThat(submittedRows).isEqualTo(1);

        HttpResponse<byte[]> xlsx = bytes(base, teacher, "/api/reports/offerings.xlsx?offeringIds=" + offeringId);
        assertThat(xlsx.statusCode()).isEqualTo(200);
        assertThat(xlsx.body().length).isGreaterThan(100);
        HttpResponse<byte[]> pdf = bytes(base, teacher, "/api/reports/offerings.pdf?offeringIds=" + offeringId);
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("D-14：独立作业（无试卷）出现在按作业聚合里，标题来自快照而非 papers")
    void standaloneAssignmentAppearsInAggregate() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");

        String classId = post(base, teacher, "/api/classes", Map.of("name", "独立作业聚合班"), "id");
        String offeringId = post(base, teacher, "/api/offerings", Map.of(
                "title", "独立作业聚合标题",
                "instructions", "写一篇 100 词的短文，介绍你的家乡。",
                "totalPoints", 20,
                "rubricMarkdown", "# 评分标准\n- 内容要点\n- 语言准确性",
                "timingMode", "INDIVIDUAL",
                "dueAt", "2026-12-31T23:59:59Z",
                "classes", List.of(Map.of("classId", classId))), "id");
        post(base, teacher, "/api/offerings/" + offeringId + "/publish", Map.of(), "id");

        List<?> offerings = Json.parseList(request(base, teacher, "GET",
                "/api/reports/offerings?offeringIds=" + offeringId, null).body());
        assertThat(offerings).as("left join 没兜住就会整行消失").hasSize(1);
        Map<?, ?> offering = (Map<?, ?>) offerings.get(0);
        assertThat(offering.get("paperTitle")).as("标题必须回退到快照 title").isEqualTo("独立作业聚合标题");
        assertThat(((Number) offering.get("expected")).longValue()).isZero();

        HttpResponse<byte[]> xlsx = bytes(base, teacher,
                "/api/reports/offerings.xlsx?offeringIds=" + offeringId);
        assertThat(xlsx.statusCode()).isEqualTo(200);
        assertThat(xlsx.body().length).isGreaterThan(100);
    }

    private String login(String base, String email) throws Exception {
        HttpResponse<String> res = request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(res.statusCode()).isEqualTo(200);
        return res.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
    }

    private String post(String base, String cookie, String path, Object body, String field) throws Exception {
        Map<String, Object> parsed = Json.parse(request(base, cookie, "POST", path, body).body());
        return parsed.get(field).toString();
    }

    private HttpResponse<byte[]> bytes(String base, String cookie, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (cookie != null) b.header("Cookie", cookie);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
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
