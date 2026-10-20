package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
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
 * T120 / T121 / T124 验收：班级汇总 Markdown 与逐份反馈 ZIP。
 *
 * <p>覆盖：summary.md 的统计表头与中文姓名、平均分与 class-summary 一致、
 * 未发布成绩时 ZIP 409、学生 403、ZIP 条目数 = 已交人数、
 * 解压后 front matter / 错题标准答案 / 中文不乱码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ExportTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // 导出是只读的，关掉后台作业避免与断言无关的写放大
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired
    Environment env;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    private final HttpClient client = HttpClient.newHttpClient();

    private String base;
    private String teacher;

    @BeforeEach
    void setUp() throws Exception {
        base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        teacher = login("teacher@teachloop.local");
    }

    // ================= 主链路：summary.md 口径 =================

    @Test
    @DisplayName("T121：summary.md 含两名学生与统计表头，平均分与 class-summary 一致")
    void summaryMarkdownMatchesClassSummary() throws Exception {
        Fixture f = fixture();
        submit(f.studentA(), f.offeringId(), answers(1, "A", 3, "我的作文内容。"), "我的作文内容。");
        submit(f.studentB(), f.offeringId(), answers(1, "C"), "乙的作文内容。");
        publishScores(f.offeringId());

        HttpResponse<String> res = raw(teacher, "GET", summaryPath(f.offeringId()));
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("content-type").orElse(""))
                .as("Markdown 必须声明 UTF-8，否则中文会乱码")
                .startsWith("text/markdown")
                .contains("charset=utf-8");
        assertThat(res.headers().firstValue("content-disposition").orElse(""))
                .contains("attachment")
                .contains("summary-" + f.offeringId() + ".md");

        String md = res.body();
        assertThat(md).contains("导出学生甲").contains("导出学生乙");
        assertThat(md).contains("## 按班统计").contains("## 逐份提交").contains("## 未提交名单");
        assertThat(md).contains("按时提交率").contains("平均分");

        // 统计数字：2 应交、2 实交、2 按时、0 迟交、0 拒收、100%、平均 5.00
        Map<String, Object> classRow = firstClassSummaryRow(f.offeringId());
        assertThat(number(classRow, "expected")).isEqualTo(2);
        assertThat(number(classRow, "submitted")).isEqualTo(2);
        assertThat(number(classRow, "onTime")).isEqualTo(2);
        assertThat(number(classRow, "late")).isZero();
        assertThat(number(classRow, "rejected")).isZero();

        List<String> cells = tableRow(md, f.classId());
        assertThat(cells).as("按班统计缺少班级行：%s", f.classId()).isNotEmpty();
        assertThat(cells.get(1)).isEqualTo(f.classId());
        assertThat(cells.get(3)).isEqualTo("2");
        assertThat(cells.get(4)).isEqualTo("2");
        assertThat(cells.get(5)).isEqualTo("2");
        assertThat(cells.get(6)).isEqualTo("0");
        assertThat(cells.get(7)).isEqualTo("0");
        assertThat(cells.get(8)).isEqualTo(rate(classRow.get("onTimeRate")));
        assertThat(cells.get(9))
                .as("平均分必须与 class-summary 同源")
                .isEqualTo(decimalText(classRow.get("averageScore")))
                .isEqualTo("5.00");

        // 逐份提交表：A 客观 10、B 客观 0，总分同为 10.00 / 0.00
        assertThat(md).contains("| 导出学生甲 | 1 |").contains("10.00");
        assertThat(md).contains("| 导出学生乙 | 1 |").contains("0.00");
    }

    // ================= 主链路：feedback.zip =================

    @Test
    @DisplayName("T120/T124：ZIP 条目数 = 已交人数，front matter、错题答案与中文正确")
    void feedbackZipContainsEachSubmittedStudent() throws Exception {
        Fixture f = fixture();
        Map<String, Object> aSubmission = submit(f.studentA(), f.offeringId(),
                answers(1, "A", 3, "我的作文内容。"), "我的作文内容。");
        submit(f.studentB(), f.offeringId(), answers(1, "C"), "乙的作文内容。");
        // 复核评语应出现在 A 的 Markdown 里（review 状态不影响 comment 是否带出）
        send(teacher, "POST", "/api/submissions/" + aSubmission.get("submissionId") + "/review",
                Map.of("status", "APPROVED", "comment", "结构完整，注意论证层次。"));
        publishScores(f.offeringId());

        HttpResponse<byte[]> res = rawBytes(teacher, "GET", zipPath(f.offeringId()));
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("content-type").orElse("")).startsWith("application/zip");
        assertThat(res.headers().firstValue("content-disposition").orElse(""))
                .contains("feedback-" + f.offeringId() + ".zip");

        Map<String, String> entries = unzip(res.body());
        assertThat(entries).as("只有已交卷且未拒收的两位学生").hasSize(2);
        assertThat(entries.keySet()).allMatch(name ->
                name.startsWith("feedback/") && name.endsWith("-attempt1.md"));
        assertThat(entries.keySet()).contains(
                "feedback/导出学生甲-attempt1.md",
                "feedback/导出学生乙-attempt1.md");

        String aMd = entries.get("feedback/导出学生甲-attempt1.md");
        assertThat(aMd).startsWith("---\n");
        assertThat(aMd).contains("student: \"导出学生甲\"");
        assertThat(aMd).contains("offering: \"导出测试卷\"");
        assertThat(aMd).contains("attempt: 1");
        assertThat(aMd).contains("objective: 10.00");
        assertThat(aMd).contains("subjective: 0.00");
        assertThat(aMd).contains("total: 10.00");
        assertThat(aMd).contains("late_seconds: 0");
        assertThat(aMd).contains("auto_submitted: false");
        assertThat(aMd).contains("## 复核评语").contains("结构完整，注意论证层次。");

        String bMd = entries.get("feedback/导出学生乙-attempt1.md");
        assertThat(bMd).contains("student: \"导出学生乙\"");
        assertThat(bMd).contains("objective: 0.00").contains("total: 0.00");
        assertThat(bMd).contains("## 错题");
        assertThat(bMd)
                .as("B 第 1 题答 C、标准答案 A")
                .contains("| 1 | 单选 | C | A | 10.00 |");
    }

    // ================= 权限与已发布闸门 =================

    @Test
    @DisplayName("T124：未发布成绩时 ZIP 409，summary.md 平均分写「未发布」")
    void zipIsBlockedUntilScoresPublished() throws Exception {
        Fixture f = fixture();
        submit(f.studentA(), f.offeringId(), answers(1, "A", 3, "我的作文内容。"), "我的作文内容。");
        submit(f.studentB(), f.offeringId(), answers(1, "C"), "乙的作文内容。");

        HttpResponse<String> zip = raw(teacher, "GET", zipPath(f.offeringId()));
        assertThat(zip.statusCode()).isEqualTo(409);
        assertThat(zip.body()).contains("成绩尚未发布");

        HttpResponse<String> summary = raw(teacher, "GET", summaryPath(f.offeringId()));
        assertThat(summary.statusCode()).isEqualTo(200);
        List<String> cells = tableRow(summary.body(), f.classId());
        assertThat(cells).isNotEmpty();
        assertThat(cells.get(9)).as("未发布时不给平均分").isEqualTo("未发布");
        assertThat(firstClassSummaryRow(f.offeringId()).get("averageScore")).isNull();
    }

    @Test
    @DisplayName("T121/T124：学生访问两个导出都是 403")
    void studentsCannotExport() throws Exception {
        Fixture f = fixture();

        assertThat(raw(f.studentA().cookie(), "GET", summaryPath(f.offeringId())).statusCode())
                .isEqualTo(403);
        assertThat(raw(f.studentA().cookie(), "GET", zipPath(f.offeringId())).statusCode())
                .isEqualTo(403);
    }

    // ================= fixtures / helpers =================

    private record StudentInfo(String cookie, String displayName) {
    }

    private record Fixture(String classId, String offeringId, StudentInfo studentA, StudentInfo studentB) {
    }

    /** 1 份 2 单选 + 1 作文的已发布试卷、1 个班（2 名新学生）、1 场已发布作业。 */
    private Fixture fixture() throws Exception {
        String paperId = post("/api/papers", Map.of("title", "导出测试卷", "defaultDurationMinutes", 60), "id");
        String sectionId = post("/api/papers/" + paperId + "/sections",
                Map.of("title", "选择与写作", "pointsPerItem", 10), "id");
        addItem(paperId, sectionId, 1, "SINGLE_CHOICE", "第 1 题题干", "[\"A\",\"B\",\"C\"]", "A", 10);
        addItem(paperId, sectionId, 2, "SINGLE_CHOICE", "第 2 题题干", "[\"A\",\"B\",\"C\"]", "B", 10);
        addItem(paperId, sectionId, 3, "ESSAY", "作文题：写一段说明。", null, null, 10);
        post("/api/papers/" + paperId + "/publish", Map.of(), "id");

        String classId = post("/api/classes", Map.of("name", "导出班 " + UUID.randomUUID()), "id");
        String invite = classInvite(classId);
        StudentInfo a = newStudent("导出学生甲");
        StudentInfo b = newStudent("导出学生乙");
        join(a.cookie(), invite);
        join(b.cookie(), invite);

        Map<String, Object> body = new HashMap<>();
        body.put("paperId", paperId);
        body.put("kind", "ASSIGNMENT");
        body.put("timingMode", "INDIVIDUAL");
        body.put("dueAt", Instant.now().plus(7, ChronoUnit.DAYS).toString());
        body.put("durationMinutes", 60);
        body.put("classes", List.of(Map.of("classId", classId)));
        String offeringId = send(teacher, "POST", "/api/offerings", body).get("id").toString();
        send(teacher, "POST", "/api/offerings/" + offeringId + "/publish", Map.of());
        return new Fixture(classId, offeringId, a, b);
    }

    private void addItem(String paperId, String sectionId, int number, String questionType,
                         String stem, String options, String answerKey, int points) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("number", number);
        body.put("questionType", questionType);
        body.put("stem", stem);
        body.put("options", options);
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

    private Map<String, Object> submit(StudentInfo student, String offeringId,
                                       Map<Integer, String> answers, String contentText)
            throws Exception {
        send(student.cookie(), "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, String> e : answers.entrySet()) {
            rows.add(Map.of("number", e.getKey(), "answer", e.getValue()));
        }
        return send(student.cookie(), "POST", "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", contentText, "answers", rows));
    }

    private void publishScores(String offeringId) throws Exception {
        send(teacher, "POST", "/api/offerings/" + offeringId + "/scores/publish", Map.of());
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

    private StudentInfo newStudent(String displayName) throws Exception {
        String email = "export-" + UUID.randomUUID() + "@teachloop.local";
        users.save(new User(UUID.randomUUID(), UserRole.STUDENT, email,
                encoder.encode("teachloop123"), displayName, Instant.now()));
        return new StudentInfo(login(email), displayName);
    }

    private String summaryPath(String offeringId) {
        return "/api/offerings/" + offeringId + "/export/summary.md";
    }

    private String zipPath(String offeringId) {
        return "/api/offerings/" + offeringId + "/export/feedback.zip";
    }

    private Map<String, Object> firstClassSummaryRow(String offeringId) throws Exception {
        List<?> rows = getList("/api/offerings/" + offeringId + "/class-summary");
        assertThat(rows).isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) rows.get(0);
        return row;
    }

    /** 取 Markdown 表格中以 firstCell 开头的行，按 | 切成 trim 后的单元格。 */
    private List<String> tableRow(String markdown, String firstCell) {
        return Arrays.stream(markdown.split("\n"))
                .filter(line -> line.startsWith("| " + firstCell + " |"))
                .findFirst()
                .map(line -> Arrays.stream(line.split("\\|", -1)).map(String::trim).toList())
                .orElse(List.of());
    }

    private String decimalText(Object value) {
        assertThat(value).as("class-summary 缺少 averageScore").isNotNull();
        return new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String rate(Object value) {
        assertThat(value).as("class-summary 缺少 onTimeRate").isNotNull();
        return new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private long number(Map<String, Object> row, String field) {
        Object value = row.get(field);
        assertThat(value).as("class-summary 缺少 %s", field).isNotNull();
        return ((Number) value).longValue();
    }

    private Map<String, String> unzip(byte[] data) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                out.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
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
        return client.send(request(cookie, method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> rawBytes(String cookie, String method, String path) throws Exception {
        return client.send(request(cookie, method, path, null), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest request(String cookie, String method, String path, Object body) {
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
        return b.build();
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
