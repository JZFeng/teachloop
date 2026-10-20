package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * T152 / T192 验收：报表 Top N 错题聚合。
 *
 * <p>覆盖：跨学生聚合与正确率排序、学生 403、班级筛选、提交时间窗口、
 * 同一学生多次 attempt 只按最新一次统计。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReportWrongItemsTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // 报表只读业务库，关掉后台作业避免与断言无关的写放大
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    private final HttpClient client = HttpClient.newHttpClient();

    private String base;
    private String teacher;
    private String studentA;
    private String studentAEmail;

    @BeforeEach
    void setUp() throws Exception {
        base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        teacher = login("teacher@teachloop.local");
        studentAEmail = "student@teachloop.local";
        studentA = login(studentAEmail);
    }

    // ================= 主链路：聚合口径与排序 =================

    @Test
    @DisplayName("T152/T192：题2 两人全错、题1 一人错、题3 仅一人作答；按正确率升序")
    void aggregatesWrongItemsAcrossStudents() throws Exception {
        Fixture f = fixture();
        String studentB = newStudent("报表学生 B");
        join(studentB, classInvite(f.classId()));

        // A：题1 对、题2 错选 C、题3 未答
        submitAs(studentA, f.offeringId(), answers(1, "A", 2, "C"));
        // B：题1 错、题2 错选 C、题3 对
        submitAs(studentB, f.offeringId(), answers(1, "B", 2, "C", 3, "C"));

        Map<String, Object> report = get(teacher, wrongItemsPath(f.offeringId()));
        List<Map<String, Object>> items = items(report);

        assertThat(report.get("totalItems")).as("统计范围覆盖全部有作答的客观题").isEqualTo(3);
        assertThat(report.get("offeringCount")).isEqualTo(1);
        assertThat(items).extracting(i -> ((Number) i.get("number")).intValue())
                .as("按正确率升序：题2(0%) → 题1(50%) → 题3(100%)")
                .containsExactly(2, 1, 3);

        Map<String, Object> item2 = itemByNumber(items, 2);
        assertThat(number(item2, "answered")).isEqualTo(2);
        assertThat(number(item2, "wrong")).isEqualTo(2);
        assertThat(decimal(item2, "correctRate")).isEqualTo(0.0);
        assertThat(item2.get("topWrongAnswer")).isEqualTo("C");
        assertThat(number(item2, "topWrongCount")).isEqualTo(2);
        assertThat(item2.get("questionType")).isEqualTo("SINGLE_CHOICE");
        assertThat(item2.get("stem")).isEqualTo("第 2 题题干");
        assertThat(decimal(item2, "points")).isEqualTo(10.0);

        Map<String, Object> item1 = itemByNumber(items, 1);
        assertThat(number(item1, "answered")).isEqualTo(2);
        assertThat(number(item1, "wrong")).isEqualTo(1);
        assertThat(decimal(item1, "correctRate")).isEqualTo(50.0);
        assertThat(item1.get("topWrongAnswer")).isEqualTo("B");
        assertThat(number(item1, "topWrongCount")).isEqualTo(1);

        Map<String, Object> item3 = itemByNumber(items, 3);
        assertThat(number(item3, "answered")).isEqualTo(1);
        assertThat(number(item3, "wrong")).isZero();
        assertThat(decimal(item3, "correctRate")).isEqualTo(100.0);
        assertThat(item3.get("topWrongAnswer")).isNull();
        assertThat(number(item3, "topWrongCount")).isZero();
        assertThat(decimal(item3, "points")).isEqualTo(5.0);

        // limit 只截断返回，totalItems 仍是完整统计范围（AC-17.10）
        Map<String, Object> limited = get(teacher, wrongItemsPath(f.offeringId()) + "&limit=1");
        assertThat(items(limited)).extracting(i -> ((Number) i.get("number")).intValue())
                .containsExactly(2);
        assertThat(limited.get("totalItems")).as("截断不影响统计范围").isEqualTo(3);
    }

    // ================= 权限 =================

    @Test
    @DisplayName("T192：学生访问报表返回 403")
    void studentForbidden() throws Exception {
        Fixture f = fixture();
        HttpResponse<String> res = raw(studentA, "GET", wrongItemsPath(f.offeringId()));
        assertThat(res.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("T192：offeringIds 缺失或 limit 越界返回 400")
    void validatesParameters() throws Exception {
        Fixture f = fixture();
        assertThat(raw(teacher, "GET", "/api/reports/wrong-items").statusCode()).isEqualTo(400);
        assertThat(raw(teacher, "GET", wrongItemsPath(f.offeringId()) + "&limit=0").statusCode())
                .isEqualTo(400);
        assertThat(raw(teacher, "GET", wrongItemsPath(f.offeringId()) + "&limit=101").statusCode())
                .isEqualTo(400);
    }

    // ================= 班级筛选 =================

    @Test
    @DisplayName("T192：classIds 传无关班级 → 空；传对应班级 → 有数据")
    void classFilterNarrowsOfferings() throws Exception {
        Fixture f = fixture();
        submitAs(studentA, f.offeringId(), answers(1, "A", 2, "C"));

        Map<String, Object> unrelated = get(teacher,
                wrongItemsPath(f.offeringId()) + "&classIds=" + UUID.randomUUID());
        assertThat(items(unrelated)).isEmpty();
        assertThat(unrelated.get("totalItems")).isEqualTo(0);
        assertThat(unrelated.get("offeringCount")).isEqualTo(0);

        Map<String, Object> matching = get(teacher,
                wrongItemsPath(f.offeringId()) + "&classIds=" + f.classId());
        assertThat(items(matching)).isNotEmpty();
        assertThat(matching.get("totalItems")).isEqualTo(2);
    }

    // ================= 时间窗口 =================

    @Test
    @DisplayName("T192：to 在过去 → 空；from 在过去 → 有数据")
    void submissionTimeWindowFilters() throws Exception {
        Fixture f = fixture();
        submitAs(studentA, f.offeringId(), answers(1, "A", 2, "C"));

        String past = URLEncoder.encode(Instant.now().minus(1, ChronoUnit.HOURS).toString(),
                StandardCharsets.UTF_8);
        String earlier = URLEncoder.encode(Instant.now().minus(1, ChronoUnit.DAYS).toString(),
                StandardCharsets.UTF_8);

        Map<String, Object> beforeSubmitted = get(teacher, wrongItemsPath(f.offeringId()) + "&to=" + past);
        assertThat(items(beforeSubmitted)).isEmpty();
        assertThat(beforeSubmitted.get("totalItems")).isEqualTo(0);

        Map<String, Object> afterFrom = get(teacher, wrongItemsPath(f.offeringId()) + "&from=" + earlier);
        assertThat(items(afterFrom)).hasSize(2);
        assertThat(afterFrom.get("totalItems")).isEqualTo(2);
        assertThat(afterFrom.get("offeringCount")).isEqualTo(1);
    }

    // ================= 多次 attempt 只取最新 =================

    @Test
    @DisplayName("T192：同一学生先错后对，只按最新 attempt 统计，题1 wrong 不增加")
    void onlyLatestAttemptIsCounted() throws Exception {
        Fixture f = fixture();

        Map<String, Object> first = submitAs(studentA, f.offeringId(), answers(1, "B", 2, "C"));
        Map<String, Object> beforeRetry = get(teacher, wrongItemsPath(f.offeringId()));
        assertThat(number(itemByNumber(items(beforeRetry), 1), "wrong")).as("第一次答错").isEqualTo(1);

        // 模拟「重新作答」：上一份 attempt 置为非当前，学生再次 start 会得到 attempt_no=2
        UUID firstSubmission = UUID.fromString(first.get("submissionId").toString());
        jdbc.update("update submissions set active = false where id = ?", firstSubmission);
        Map<String, Object> second = submitAs(studentA, f.offeringId(), answers(1, "A", 2, "C"));
        assertThat(number(second, "attemptNo")).isEqualTo(2);

        Map<String, Object> afterRetry = get(teacher, wrongItemsPath(f.offeringId()));
        Map<String, Object> item1 = itemByNumber(items(afterRetry), 1);
        assertThat(number(item1, "answered")).as("同一学生只计一次").isEqualTo(1);
        assertThat(number(item1, "wrong")).as("最新 attempt 答对，不再计入第一次的错").isZero();
        assertThat(decimal(item1, "correctRate")).isEqualTo(100.0);
        assertThat(afterRetry.get("offeringCount")).isEqualTo(1);
    }

    // ================= fixtures / helpers =================

    private record Fixture(String classId, String offeringId) {
    }

    /** 一份 3 道单选（10/10/5 分）的已发布试卷、1 个班、1 场已发布作业。 */
    private Fixture fixture() throws Exception {
        String paperId = post("/api/papers", Map.of("title", "T192 测试卷", "defaultDurationMinutes", 60), "id");
        String sectionId = post("/api/papers/" + paperId + "/sections",
                Map.of("title", "选择题", "pointsPerItem", 10), "id");
        addSingleChoice(paperId, sectionId, 1, "第 1 题题干", "A", 10);
        addSingleChoice(paperId, sectionId, 2, "第 2 题题干", "B", 10);
        addSingleChoice(paperId, sectionId, 3, "第 3 题题干", "C", 5);
        post("/api/papers/" + paperId + "/publish", Map.of(), "id");

        String classId = post("/api/classes", Map.of("name", "报表班 " + UUID.randomUUID()), "id");
        join(studentA, classInvite(classId));

        Map<String, Object> body = new HashMap<>();
        body.put("paperId", paperId);
        body.put("kind", "ASSIGNMENT");
        body.put("timingMode", "INDIVIDUAL");
        body.put("dueAt", Instant.now().plus(7, ChronoUnit.DAYS).toString());
        body.put("durationMinutes", 60);
        body.put("classes", List.of(Map.of("classId", classId)));
        String offeringId = send(teacher, "POST", "/api/offerings", body).get("id").toString();
        send(teacher, "POST", "/api/offerings/" + offeringId + "/publish", Map.of());
        return new Fixture(classId, offeringId);
    }

    private void addSingleChoice(String paperId, String sectionId, int number, String stem,
                                 String answerKey, int points) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("number", number);
        body.put("questionType", "SINGLE_CHOICE");
        body.put("stem", stem);
        body.put("options", "[\"A\",\"B\",\"C\"]");
        body.put("answerKey", answerKey);
        body.put("points", points);
        body.put("sectionId", sectionId);
        post("/api/papers/" + paperId + "/items", body, "id");
    }

    private Map<Integer, String> answers(Object... numberAnswerPairs) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (int i = 0; i < numberAnswerPairs.length; i += 2) {
            out.put((Integer) numberAnswerPairs[i], (String) numberAnswerPairs[i + 1]);
        }
        return out;
    }

    private Map<String, Object> submitAs(String cookie, String offeringId, Map<Integer, String> answers)
            throws Exception {
        send(cookie, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        List<Map<String, Object>> rows = answers.entrySet().stream()
                .map(e -> Map.<String, Object>of("number", e.getKey(), "answer", e.getValue()))
                .toList();
        return send(cookie, "POST", "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", "报表测试作答。", "answers", rows));
    }

    private String classInvite(String classId) throws Exception {
        for (Object o : getList("/api/classes")) {
            Map<?, ?> c = (Map<?, ?>) o;
            if (classId.equals(c.get("id"))) {
                return c.get("inviteCode").toString();
            }
        }
        throw new AssertionError("找不到班级 " + classId);
    }

    private void join(String cookie, String inviteCode) throws Exception {
        assertThat(inviteCode).isNotNull();
        send(cookie, "POST", "/api/classes/join", Map.of("inviteCode", inviteCode));
    }

    private String newStudent(String displayName) throws Exception {
        String email = "report-" + UUID.randomUUID() + "@teachloop.local";
        users.save(new User(UUID.randomUUID(), UserRole.STUDENT, email,
                encoder.encode("teachloop123"), displayName, Instant.now()));
        return login(email);
    }

    private String wrongItemsPath(String offeringId) {
        return "/api/reports/wrong-items?offeringIds=" + offeringId;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("items");
    }

    private Map<String, Object> itemByNumber(List<Map<String, Object>> items, int number) {
        return items.stream()
                .filter(i -> ((Number) i.get("number")).intValue() == number)
                .findFirst()
                .orElseThrow(() -> new AssertionError("报表缺少第 " + number + " 题"));
    }

    private long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private double decimal(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).doubleValue();
    }

    private String login(String email) throws Exception {
        HttpResponse<String> res = raw(null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
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

    private List<?> getList(String path) throws Exception {
        HttpResponse<String> res = raw(teacher, "GET", path, null);
        assertThat(res.statusCode()).withFailMessage("GET %s -> %s %s", path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parseList(res.body());
    }

    private String post(String path, Object body, String field) throws Exception {
        Object value = send(teacher, "POST", path, body).get(field);
        assertThat(value).as("%s 返回缺少字段 %s", path, field).isNotNull();
        return value.toString();
    }

    private Map<String, Object> send(String cookie, String method, String path, Object body)
            throws Exception {
        HttpResponse<String> res = raw(cookie, method, path, body);
        assertThat(res.statusCode()).withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private HttpResponse<String> raw(String cookie, String method, String path) throws Exception {
        return raw(cookie, method, path, null);
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
