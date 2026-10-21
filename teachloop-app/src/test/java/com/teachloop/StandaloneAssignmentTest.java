package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.grading.domain.ReviewStatus;
import com.teachloop.grading.service.GradingWorker;
import com.teachloop.shared.port.Ports;
import com.teachloop.sitting.domain.GradingState;
import com.teachloop.sitting.domain.Offering;
import com.teachloop.sitting.domain.OfferingRepository;
import com.teachloop.sitting.domain.Submission;
import com.teachloop.sitting.domain.SubmissionRepository;
import com.teachloop.sitting.service.SnapshotCodec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D-14「严格版」：作业与考试在数据层分开——作业自带内容、不引用试卷。
 *
 * <p>覆盖：无 paperId 建作业（发布即生效）→ 数据层断言（paper_id 为空、快照 1 条 ESSAY、
 * paper_version=0、无时长）→ 学生看到题目要求与 rubric → 提交 → 派发 → worker 批改
 * → 主观分 &gt; 0 且复核 PENDING；以及三种非法组合的 400。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StandaloneAssignmentTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.grading.poll-ms", () -> "3600000"); // 关掉后台轮询，测试里手动跑一批
        r.add("teachloop.grading.dispatch-ms", () -> "3600000"); // 关掉自动派发，测试里手动派发
        r.add("teachloop.grading.sweep-ms", () -> "3600000"); // 关掉定时清扫，避免干扰手动流程
    }

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OfferingRepository offerings;

    @Autowired
    SubmissionRepository submissions;

    @Autowired
    ReviewRepository reviews;

    @Autowired
    GradingWorker worker;

    @Autowired
    SnapshotCodec codec;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("D-14：独立作业无 paper_id，内容进快照，学生可见题目与 rubric，批改后主观分>0 待复核")
    void standaloneAssignmentEndToEnd() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        Map<String, Object> cls = send(base, teacher, "POST", "/api/classes",
                Map.of("name", "独立作业班"));
        String classId = cls.get("id").toString();
        String invite = cls.get("inviteCode").toString();

        String instructions = "请写一篇 120 词短文，谈谈你的暑假计划。";
        String offeringId = post(base, teacher, "/api/offerings", Map.of(
                "title", "暑假计划（独立作业）",
                "instructions", instructions,
                "totalPoints", 20,
                "rubricMarkdown", "# 评分标准\n- 内容要点：切题\n- 语言准确性：语法正确",
                "dueAt", "2026-12-31T23:59:59Z",
                "timingMode", "INDIVIDUAL",
                "classes", List.of(Map.of("classId", classId)),
                "publish", true), "id");

        // ① 数据层：作业不引用试卷，内容快照成 1 条 ESSAY；kind 由内容来源派生。
        UUID offeringUuid = UUID.fromString(offeringId);
        Map<String, Object> row = jdbc.queryForMap(
                "select kind, paper_id, paper_version, duration_minutes, timing_mode "
                        + "from offerings where id = ?", offeringUuid);
        assertThat(row.get("paper_id")).as("作业的 paper_id 必须为空").isNull();
        assertThat(row.get("kind")).isEqualTo("ASSIGNMENT");
        assertThat(((Number) row.get("paper_version")).intValue()).isZero();
        assertThat(row.get("duration_minutes")).as("作业不设时长").isNull();
        assertThat(row.get("timing_mode")).isEqualTo("INDIVIDUAL");

        Offering offering = offerings.findById(offeringUuid).orElseThrow();
        Ports.PaperSnapshot snap = codec.read(offering.getPaperSnapshot());
        assertThat(snap.title()).isEqualTo("暑假计划（独立作业）");
        assertThat(snap.paperVersion()).isZero();
        assertThat(snap.totalPoints()).isEqualTo(20);
        assertThat(snap.durationMinutes()).isZero();
        assertThat(snap.items()).as("作业只有 1 条合成题项").hasSize(1);
        Ports.ItemSpec item = snap.items().get(0);
        assertThat(item.questionType()).isEqualTo("ESSAY");
        assertThat(item.number()).isEqualTo(1);
        assertThat(item.stem()).isEqualTo(instructions);
        assertThat(item.points()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(item.sectionTitle()).isEqualTo("作业要求");
        assertThat(snap.rubricMarkdown()).contains("内容要点");

        // ② 接口视图：paperId 为 null，标题仍取快照 title（view() 的唯一 NPE 点）。
        Map<String, Object> detail = get(base, teacher, "/api/offerings/" + offeringId);
        assertThat(detail.get("paperId")).isNull();
        assertThat(detail.get("paperTitle")).isEqualTo("暑假计划（独立作业）");
        assertThat(detail.get("kind")).isEqualTo("ASSIGNMENT");
        assertThat(detail.get("status")).as("publish=true 必须真的落成 PUBLISHED").isEqualTo("PUBLISHED");

        // ③ 学生入口：rubric 可见；/paper 返回题目要求。
        post(base, student, "/api/classes/join", Map.of("inviteCode", invite), "name");
        Map<String, Object> entry = get(base, student, "/api/offerings/" + offeringId + "/entry");
        assertThat(entry.get("rubric")).as("作业必须让学生看到评分标准").isNotNull();
        assertThat(entry.get("rubric").toString()).contains("内容要点").contains("语言准确性");
        assertThat(((Number) entry.get("itemCount")).intValue()).isEqualTo(1);
        assertThat(((Number) entry.get("totalPoints")).intValue()).isEqualTo(20);

        Map<String, Object> paper = get(base, student, "/api/offerings/" + offeringId + "/paper");
        List<?> items = (List<?>) paper.get("items");
        assertThat(items).hasSize(1);
        assertThat(((Map<?, ?>) items.get(0)).get("stem")).as("题目要求就是作业内容").isEqualTo(instructions);

        // ④ 提交 → 派发 → worker 批改：主观分 > 0，落复核队列 PENDING。
        send(base, student, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        // 合成题项在 objective_items 没有行：客户端误给 ESSAY 题号传 answers[] 时不能 500，
        // 正文仍走 contentText（D-14 的外键守卫）。
        send(base, student, "PUT", "/api/offerings/" + offeringId + "/answers",
                Map.of("answers", List.of(Map.of("number", 1, "answer", "ignored"))));
        String submissionId = post(base, student, "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", "This summer I plan to read more and learn to swim."),
                "submissionId");

        Map<String, Object> dispatch = send(base, teacher, "POST",
                "/api/offerings/" + offeringId + "/grading/dispatch", Map.of());
        assertThat(((Number) dispatch.get("enqueued")).intValue()).isEqualTo(1);
        worker.processBatch(10);

        Submission s = submissions.findById(UUID.fromString(submissionId)).orElseThrow();
        assertThat(s.getGradingState()).isEqualTo(GradingState.DONE);
        assertThat(s.getObjectiveTotal()).as("作业没有客观题，客观分恒为 0").isEqualByComparingTo("0");
        assertThat(s.getSubjectiveTotal()).as("独立作业必须有主观分").isGreaterThan(BigDecimal.ZERO);
        assertThat(reviews.findBySubmissionId(s.getId()).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    @DisplayName("D-14：kind 与内容来源必须一致；作业不接受考试计时字段")
    void rejectsInconsistentContentSource() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String classId = post(base, teacher, "/api/classes", Map.of("name", "校验班"), "id");
        List<Map<String, Object>> classes = List.of(Map.of("classId", classId));

        // ① 有试卷却声称是作业
        HttpResponse<String> paperAsAssignment = raw(base, teacher, "POST", "/api/offerings", Map.of(
                "paperId", UUID.randomUUID().toString(),
                "kind", "ASSIGNMENT",
                "classes", classes));
        assertThat(paperAsAssignment.statusCode()).isEqualTo(400);
        assertThat(paperAsAssignment.body()).contains("作业不能引用试卷");

        // ② 没有试卷却声称是考试
        HttpResponse<String> noPaperAsExam = raw(base, teacher, "POST", "/api/offerings", Map.of(
                "kind", "EXAM",
                "title", "无卷考试",
                "instructions", "随便写点",
                "totalPoints", 10,
                "classes", classes));
        assertThat(noPaperAsExam.statusCode()).isEqualTo(400);
        assertThat(noPaperAsExam.body()).contains("考试必须引用试卷");

        // ③ 作业带考试计时字段：时长 / 统一开考时间 / 按班覆盖 一律拒绝
        HttpResponse<String> assignmentWithDuration = raw(base, teacher, "POST", "/api/offerings", Map.of(
                "title", "带时长的作业",
                "instructions", "随便写点",
                "totalPoints", 10,
                "durationMinutes", 60,
                "classes", classes));
        assertThat(assignmentWithDuration.statusCode()).isEqualTo(400);
        assertThat(assignmentWithDuration.body()).contains("作业不支持考试计时字段");

        HttpResponse<String> assignmentFixedWindow = raw(base, teacher, "POST", "/api/offerings", Map.of(
                "title", "统一开考的作业",
                "instructions", "随便写点",
                "totalPoints", 10,
                "timingMode", "FIXED_WINDOW",
                "classes", classes));
        assertThat(assignmentFixedWindow.statusCode()).isEqualTo(400);
        assertThat(assignmentFixedWindow.body()).contains("作业不支持考试计时字段");

        HttpResponse<String> assignmentWithClassOverride = raw(base, teacher, "POST", "/api/offerings",
                Map.of(
                        "title", "按班覆盖的作业",
                        "instructions", "随便写点",
                        "totalPoints", 10,
                        "classes", List.of(Map.of("classId", classId,
                                "dueAtOverride", "2026-12-31T23:59:59Z"))));
        assertThat(assignmentWithClassOverride.statusCode()).isEqualTo(400);
        assertThat(assignmentWithClassOverride.body()).contains("作业不支持考试计时字段");
    }

    // ---------- helpers ----------

    private String login(String base, String email) throws Exception {
        HttpResponse<String> res = request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(res.statusCode()).as("login %s: %s", email, res.body()).isEqualTo(200);
        List<String> cookies = res.headers().allValues("set-cookie");
        return cookies.stream()
                .map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("登录没有下发会话 cookie：" + cookies));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpResponse<String> res = request(base, cookie, method, path, body);
        assertThat(res.statusCode()).withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private Map<String, Object> get(String base, String cookie, String path) throws Exception {
        return send(base, cookie, "GET", path, null);
    }

    private String post(String base, String cookie, String path, Object body, String field) throws Exception {
        Object v = send(base, cookie, "POST", path, body).get(field);
        assertThat(v).as("%s 返回缺少字段 %s", path, field).isNotNull();
        return v.toString();
    }

    private HttpResponse<String> raw(String base, String cookie, String method, String path, Object body)
            throws Exception {
        return request(base, cookie, method, path, body);
    }

    private HttpResponse<String> request(String base, String cookie, String method, String path, Object body)
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

    /** 极简 JSON 读写，避免依赖 Jackson 版本细节。 */
    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static String write(Object o) {
            return MAPPER.writeValueAsString(o);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) {
            return MAPPER.readValue(s, Map.class);
        }
    }
}
