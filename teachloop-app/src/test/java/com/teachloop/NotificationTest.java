package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.shared.port.Ports;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
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

/**
 * T208 / T114 验收：通知表在 Flyway 下建好，端口批量入库，两个学生的通知互相隔离，
 * 未读计数、单条已读、全部已读、他人的通知 404。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class NotificationTest {

    private static final String PASSWORD = "teachloop123";
    private static final String STUDENT_A = "notify-a@teachloop.local";
    private static final String STUDENT_B = "notify-b@teachloop.local";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // 与本测试无关的后台作业调远，避免日志噪声与偶发干扰
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Ports.NotificationPort notifications;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    private final HttpClient client = HttpClient.newHttpClient();

    private String base;
    private UUID studentAId;
    private UUID studentBId;
    private String studentA;
    private String studentB;

    @BeforeEach
    void setUp() throws Exception {
        base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        studentAId = ensureStudent(STUDENT_A, "通知学生甲");
        studentBId = ensureStudent(STUDENT_B, "通知学生乙");
        studentA = login(STUDENT_A);
        studentB = login(STUDENT_B);
        // 同一测试类共享一个库：通知是本测试的主体，按用例清空，避免上一用例的残留影响计数
        jdbc.update("delete from notifications");
    }

    @Test
    @DisplayName("T208+T114：V017 在 Flyway 下执行；通知批量入库并按接收者隔离")
    void migrationAndRecipientIsolation() throws Exception {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '017' and success = true",
                Integer.class);
        assertThat(applied).as("V017 notifications 迁移已成功执行").isEqualTo(1);
        Integer table = jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where table_schema = 'public' and table_name = 'notifications'", Integer.class);
        assertThat(table).isEqualTo(1);

        String offeringId = UUID.randomUUID().toString();
        notifications.notify(List.of(
                message(studentAId, "offering_published", offeringId, "新考试：第一次月考", "老师已布置新考试，请按时完成。"),
                message(studentAId, "scores_published", offeringId, "成绩已发布", "本次成绩可以查看了。"),
                message(studentBId, "offering_published", offeringId, "新考试：第一次月考", "老师已布置新考试，请按时完成。")));

        List<?> aList = getList(studentA, "/api/notifications");
        List<?> bList = getList(studentB, "/api/notifications");
        assertThat(aList).as("甲只看到自己的两条").hasSize(2);
        assertThat(bList).as("乙只看到自己的一条").hasSize(1);
        Map<?, ?> aFirst = (Map<?, ?>) aList.get(0);
        assertThat(aFirst.get("targetType")).isEqualTo("offering");
        assertThat(aFirst.get("targetId")).isEqualTo(offeringId);
        assertThat(aFirst.get("read")).isEqualTo(false);
        assertThat(aFirst.get("readAt")).isNull();
        assertThat(aFirst.get("createdAt")).isNotNull();

        assertThat(unreadCount(studentA)).isEqualTo(2);
        assertThat(unreadCount(studentB)).isEqualTo(1);

        // limit 生效；unread=true 只返回未读
        assertThat(getList(studentA, "/api/notifications?limit=1")).hasSize(1);
        assertThat(getList(studentA, "/api/notifications?unread=true")).hasSize(2);
    }

    @Test
    @DisplayName("T114：单条已读幂等、read-all、已读后未读计数正确、他人通知 404")
    void readFlowAndCrossUserIsolation() throws Exception {
        notifications.notify(List.of(
                message(studentAId, "offering_published", UUID.randomUUID().toString(), "通知一", "正文一"),
                message(studentAId, "scores_published", UUID.randomUUID().toString(), "通知二", "正文二"),
                message(studentBId, "feedback_ready", UUID.randomUUID().toString(), "反馈已就绪", "老师已完成复核。")));
        String bNotificationId = (String) ((Map<?, ?>) getList(studentB, "/api/notifications").get(0)).get("id");

        List<?> before = getList(studentA, "/api/notifications");
        assertThat(before).hasSize(2);
        String aNotificationId = (String) ((Map<?, ?>) before.get(0)).get("id");

        // 甲不能把乙的通知标已读：404，且不泄漏存在性
        assertThat(raw(studentA, "POST", "/api/notifications/" + bNotificationId + "/read", null).statusCode())
                .isEqualTo(404);
        assertThat(unreadCount(studentB)).isEqualTo(1);

        // 单条已读：read=true、readAt 落库、未读数 -1
        Map<String, Object> read = send(studentA, "POST", "/api/notifications/" + aNotificationId + "/read", null);
        assertThat(read.get("read")).isEqualTo(true);
        assertThat(read.get("readAt")).isNotNull();
        String firstReadAt = read.get("readAt").toString();
        assertThat(unreadCount(studentA)).isEqualTo(1);

        // 重复标记：幂等，保留第一次的 read_at
        Map<String, Object> again = send(studentA, "POST", "/api/notifications/" + aNotificationId + "/read", null);
        assertThat(again.get("readAt")).isEqualTo(firstReadAt);
        assertThat(unreadCount(studentA)).isEqualTo(1);

        // unread=true 过滤掉已读
        assertThat(getList(studentA, "/api/notifications?unread=true")).hasSize(1);
        assertThat(getList(studentA, "/api/notifications")).hasSize(2);

        // 全部已读：只影响自己
        Map<String, Object> readAll = send(studentA, "POST", "/api/notifications/read-all", null);
        assertThat(((Number) readAll.get("updated")).intValue()).isEqualTo(1);
        assertThat(unreadCount(studentA)).isZero();
        assertThat(unreadCount(studentB)).as("乙的通知不受影响").isEqualTo(1);
        Map<String, Object> readAllAgain = send(studentA, "POST", "/api/notifications/read-all", null);
        assertThat(((Number) readAllAgain.get("updated")).intValue()).isZero();
    }

    @Test
    @DisplayName("T208：旧的两个通知方法保持审计语义（不臆造接收者、不写 notifications 行）")
    void legacyNotifyMethodsStillAuditOnly() {
        String offeringId = UUID.randomUUID().toString();
        long before = notificationRows();
        notifications.notifyOfferingPublished(UUID.fromString(offeringId));
        notifications.notifyScoresPublished(UUID.fromString(offeringId));

        assertThat(notificationRows()).as("旧方法不写站内通知行").isEqualTo(before);
        Integer published = jdbc.queryForObject("select count(*) from audit_logs "
                + "where action = 'notification:offering_published' and target_id = ?",
                Integer.class, offeringId);
        Integer scores = jdbc.queryForObject("select count(*) from audit_logs "
                + "where action = 'notification:scores_published' and target_id = ?",
                Integer.class, offeringId);
        assertThat(published).isEqualTo(1);
        assertThat(scores).isEqualTo(1);
    }

    // ---------- helpers ----------

    @Test
    @DisplayName("T250：作业发布的通知 target_type=assignment，前端才能跳到 /assignments/{id}")
    void assignmentNotificationTargetsAssignmentPage() throws Exception {
        String teacher = login("teacher@teachloop.local");
        Map<String, Object> cls = send(teacher, "POST", "/api/classes",
                Map.of("name", "通知作业班 " + UUID.randomUUID()));
        String classId = cls.get("id").toString();
        String invite = cls.get("inviteCode").toString();
        send(studentA, "POST", "/api/classes/join", Map.of("inviteCode", invite));

        String offeringId = send(teacher, "POST", "/api/offerings", Map.of(
                "title", "通知作业",
                "instructions", "写一段话。",
                "totalPoints", 10,
                "rubricMarkdown", "# 评分标准\n- 内容要点",
                "classes", List.of(Map.of("classId", classId)),
                "publish", true)).get("id").toString();

        String targetType = jdbc.queryForObject(
                "select target_type from notifications where recipient_id = ? and kind = 'offering_published'"
                        + " and target_id = ?", String.class, studentAId, offeringId);
        assertThat(targetType).as("作业通知要能跳到作业页").isEqualTo("assignment");
    }

    private Ports.NotificationMessage message(UUID recipientId, String kind, String targetId,
                                              String title, String body) {
        return new Ports.NotificationMessage(recipientId, kind, "offering", targetId, title, body);
    }

    private long notificationRows() {
        Long count = jdbc.queryForObject("select count(*) from notifications", Long.class);
        return count == null ? 0 : count;
    }

    private long unreadCount(String cookie) throws Exception {
        return ((Number) get(cookie, "/api/notifications/unread-count").get("count")).longValue();
    }

    private UUID ensureStudent(String email, String displayName) {
        return users.findByEmail(email).map(User::getId).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            users.save(new User(id, UserRole.STUDENT, email, encoder.encode(PASSWORD), displayName,
                    Instant.now()));
            return id;
        });
    }

    private String login(String email) throws Exception {
        HttpResponse<String> res = raw(null, "POST", "/api/auth/login",
                Map.of("email", email, "password", PASSWORD));
        assertThat(res.statusCode()).as("login %s: %s", email, res.body()).isEqualTo(200);
        List<String> cookies = res.headers().allValues("set-cookie");
        return cookies.stream()
                .map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("登录没有下发会话 cookie：" + cookies));
    }

    private Map<String, Object> get(String cookie, String path) throws Exception {
        return send(cookie, "GET", path, null);
    }

    private List<?> getList(String cookie, String path) throws Exception {
        HttpResponse<String> res = raw(cookie, "GET", path, null);
        assertThat(res.statusCode()).withFailMessage("GET %s -> %s %s", path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parseList(res.body());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String cookie, String method, String path, Object body)
            throws Exception {
        HttpResponse<String> res = raw(cookie, method, path, body);
        assertThat(res.statusCode()).withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private HttpResponse<String> raw(String cookie, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static String write(Object o) {
            return MAPPER.writeValueAsString(o);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) {
            return MAPPER.readValue(s, Map.class);
        }

        static List<?> parseList(String s) {
            return MAPPER.readValue(s, List.class);
        }
    }
}
