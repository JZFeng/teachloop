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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 011 / T559 / AC-94.1 / AC-94.3：{@code GET /api/student/today} 的 HTTP 级验收。
 *
 * <p>与 {@code StudentTodayEndpointTest}（纯单元，测排序与降级）互补：这里真的过 Spring MVC、
 * 过三段端口的真实实现（vocab 的复习切片 SQL + sitting 的 AgendaStore SQL + classroom 的成员查询），
 * 断言的是「字段名、大小写、数组形状与 spec §7.1 一致」，以及非学生拿到三段空值。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.engine=stub",
                "teachloop.vocab.generation.poll-ms=3600000",
                "teachloop.grading.poll-ms=3600000",
                "teachloop.grading.dispatch-ms=3600000",
                "teachloop.grading.sweep-ms=3600000",
                "teachloop.exam.scan-ms=3600000"
        })
@Testcontainers
class StudentTodayHttpTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    private final HttpClient client = HttpClient.newHttpClient();

    private UUID studentId;
    private UUID classId;

    @BeforeEach
    void seed() {
        studentId = UUID.randomUUID();
        users.save(new User(studentId, UserRole.STUDENT,
                "today-" + UUID.randomUUID() + "@teachloop.local", encoder.encode("teachloop123"),
                "今日页学生", Instant.parse("2026-10-01T00:00:00Z")));

        UUID teacherId = users.findByEmail("teacher@teachloop.local").orElseThrow().getId();
        classId = UUID.randomUUID();
        jdbc.update("""
                insert into classes (id, name, teacher_id, invite_code, created_at)
                values (?, '初二（3）班', ?, ?, now())
                """, classId, teacherId, "T" + classId.toString().substring(0, 8).toUpperCase());
        jdbc.update("""
                insert into class_members (id, class_id, student_id, joined_at)
                values (?, ?, ?, now())
                """, UUID.randomUUID(), classId, studentId);

        // 作业：一条逾期、一条未来（验证 AC-90.2 的排序也走真实 SQL）。
        offering("Unit 5 阅读理解（一）", "2026-01-01T00:00:00Z", "ASSIGNMENT");
        offering("Unit 6 完形填空", "2099-01-01T00:00:00Z", "ASSIGNMENT");

        // 一张到期卡 + 一条今日复习日志（验证 review 段不是写死的零）。
        UUID wordId = UUID.randomUUID();
        jdbc.update("""
                insert into vocab_words (id, user_id, language, form, normalized_form,
                                         content_status, created_at, updated_at)
                values (?, ?, 'en', 'todayword', 'todayword', 'READY', now(), now())
                """, wordId, studentId);
        jdbc.update("""
                insert into vocab_progress (word_id, user_id, state, due_at, interval_days, ease,
                                            lapses, reviewed_count, updated_at)
                values (?, ?, 'REVIEW', ?, 1, 2.5, 0, 1, now())
                """, wordId, studentId, java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("""
                insert into vocab_review_logs (user_id, word_id, rating, reviewed_at)
                values (?, ?, 2, now())
                """, studentId, UUID.randomUUID());
    }

    private void offering(String title, String dueAt, String kind) {
        UUID offeringId = UUID.randomUUID();
        jdbc.update("""
                insert into offerings (id, paper_id, kind, start_at, due_at, status, paper_snapshot,
                                       paper_version, created_at, created_by)
                values (?, null, ?, null, ?, 'PUBLISHED', ?::jsonb, 1, now(), null)
                """, offeringId, kind, java.sql.Timestamp.from(Instant.parse(dueAt)),
                "{\"title\":\"" + title + "\",\"paperVersion\":1,\"totalPoints\":10,\"durationMinutes\":60,"
                        + "\"items\":[]}");
        jdbc.update("""
                insert into offering_classes (id, offering_id, class_id)
                values (?, ?, ?)
                """, UUID.randomUUID(), offeringId, classId);
    }

    @Test
    @DisplayName("AC-94.1：学生拿到的三段字段与 spec §7.1 一致，作业按逾期在前排序")
    void studentTodayHasAllSections() throws Exception {
        JsonNode body = get("/api/student/today", studentCookie(), "Asia/Shanghai");

        assertThat(body.get("review").get("due").asInt()).isEqualTo(1);
        assertThat(body.get("review").get("reviewedToday").asInt()).isEqualTo(1);
        assertThat(body.get("review").get("newToday").asInt()).isEqualTo(0);
        assertThat(body.get("review").get("newLimit").asInt()).isGreaterThan(0);

        JsonNode assignments = body.get("assignments");
        assertThat(assignments.isArray()).isTrue();
        assertThat(assignments.size()).isEqualTo(2);
        assertThat(assignments.get(0).get("title").asString()).isEqualTo("Unit 5 阅读理解（一）");
        assertThat(assignments.get(0).get("className").asString()).isEqualTo("初二（3）班");
        assertThat(assignments.get(0).get("status").asString()).isEqualTo("todo");
        assertThat(assignments.get(0).get("dueAt").asString()).startsWith("2026-01-01");
        assertThat(assignments.get(1).get("title").asString()).isEqualTo("Unit 6 完形填空");

        assertThat(body.get("exams").isArray()).isTrue();
        assertThat(body.get("exams").size()).isEqualTo(0);
        assertThat(body.get("partial").get("review").asString()).isEqualTo("ok");
        assertThat(body.get("partial").get("assignments").asString()).isEqualTo("ok");
        assertThat(body.get("partial").get("exams").asString()).isEqualTo("ok");
        assertThat(body.get("inClass").asBoolean()).isTrue();
        assertThat(body.get("zone").asString()).isEqualTo("Asia/Shanghai");
        assertThat(body.get("defaultZone").asString()).isEqualTo("Asia/Shanghai");
    }

    @Test
    @DisplayName("AC-94.3：教师调用返回三段空值，HTTP 200")
    void teacherGetsEmptySections() throws Exception {
        String cookie = login("teacher@teachloop.local");
        JsonNode body = get("/api/student/today", cookie, null);

        assertThat(body.get("review").get("due").asInt()).isZero();
        assertThat(body.get("assignments").size()).isZero();
        assertThat(body.get("exams").size()).isZero();
        assertThat(body.get("partial").get("review").asString()).isEqualTo("ok");
        assertThat(body.get("inClass").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("AC-90.5：不在任何班级的学生 inClass=false（前端换邀请码卡）")
    void noClassStudentHasInClassFalse() throws Exception {
        jdbc.update("delete from class_members where student_id = ?", studentId);
        JsonNode body = get("/api/student/today", studentCookie(), null);
        assertThat(body.get("inClass").asBoolean()).isFalse();
        assertThat(body.get("assignments").size()).isZero();
    }

    private JsonNode get(String path, String cookie, String timezone) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Cookie", cookie)
                .GET();
        if (timezone != null) {
            builder.header("X-Timezone", timezone);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private String studentCookie() throws Exception {
        return login(users.findById(studentId).orElseThrow().getEmail());
    }

    private String login(String email) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("email", email, "password", "teachloop123"));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(base() + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
