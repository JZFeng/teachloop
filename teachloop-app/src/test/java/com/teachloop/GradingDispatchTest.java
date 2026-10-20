package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.grading.service.GradingWorker;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.sitting.service.GradingDispatchPolicy;
import com.teachloop.sitting.service.GradingPeakHours;
import com.teachloop.sitting.domain.OfferingRepository;
import com.teachloop.sitting.domain.SubmissionRepository;
import com.teachloop.sitting.service.GradingStaleSweeper;
import com.teachloop.sitting.service.OfferingGradingDispatcher;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.port.Ports;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T091–T094 验收：到期派发（含 due+grace 边界）、峰谷调度、关闭开关、并发只产生一条 run、
 * 僵尸清扫、以及 progress 聚合数字。
 *
 * <p>固定可推进时钟：BASE = 2026-11-02T00:00:00Z = 北京时间周一 08:00（空闲时段），
 * 这样 +1h 正好是高峰起点 09:00、+4h 正好是 12:00 空闲起点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(GradingDispatchTest.ClockOverride.class)
class GradingDispatchTest {

    private static final Instant BASE = Instant.parse("2026-11-02T00:00:00Z");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final MutableClock CLOCK = new MutableClock(BASE);

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // 关掉三个后台作业，测试里显式调用，保证固定时钟下可确定性地断言
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.grading.poll-ms", () -> "3600000");
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OfferingGradingDispatcher dispatcher;

    @Autowired
    GradingStaleSweeper sweeper;

    @Autowired
    GradingWorker worker;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    OfferingRepository offeringRepository;

    @Autowired
    SubmissionRepository submissionRepository;

    @Autowired
    Ports.GradingDispatch gradingDispatchPort;

    @Autowired
    AuditPort auditPort;

    @Autowired
    TransactionTemplate transactionTemplate;

    private final HttpClient client = HttpClient.newHttpClient();

    private String base;
    private String teacher;
    private String student;

    @BeforeEach
    void setUp() throws Exception {
        CLOCK.set(BASE);
        base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        teacher = login("teacher@teachloop.local");
        student = login("student@teachloop.local");
    }

    // ================= T093：纯函数（8 个时刻 + 周末） =================

    @Test
    @DisplayName("T093：09:00/12:00/14:00/18:00 是区间边界；周末零延迟；关闭开关后立即派发")
    void peakHoursBoundaries() {
        GradingPeakHours hours = new GradingPeakHours();

        assertThat(hours.isDispatchableNow(beijing(2, 8, 59))).as("8:59 空闲").isTrue();
        assertThat(hours.isDispatchableNow(beijing(2, 9, 0))).as("9:00 高峰").isFalse();
        assertThat(hours.nextDispatchableAt(beijing(2, 9, 0))).isEqualTo(beijing(2, 12, 0));
        assertThat(hours.isDispatchableNow(beijing(2, 11, 59))).as("11:59 高峰").isFalse();
        assertThat(hours.nextDispatchableAt(beijing(2, 11, 59))).isEqualTo(beijing(2, 12, 0));
        assertThat(hours.isDispatchableNow(beijing(2, 12, 0))).as("12:00 空闲").isTrue();
        assertThat(hours.isDispatchableNow(beijing(2, 13, 59))).as("13:59 空闲").isTrue();
        assertThat(hours.isDispatchableNow(beijing(2, 14, 0))).as("14:00 高峰").isFalse();
        assertThat(hours.nextDispatchableAt(beijing(2, 14, 0))).isEqualTo(beijing(2, 18, 0));
        assertThat(hours.isDispatchableNow(beijing(2, 17, 59))).as("17:59 高峰").isFalse();
        assertThat(hours.nextDispatchableAt(beijing(2, 17, 59))).isEqualTo(beijing(2, 18, 0));
        assertThat(hours.isDispatchableNow(beijing(2, 18, 0))).as("18:00 空闲").isTrue();

        // 周末全天零延迟（2026-11-07 周六 / 11-08 周日）
        assertThat(hours.isDispatchableNow(beijing(7, 10, 0))).as("周六").isTrue();
        assertThat(hours.isDispatchableNow(beijing(8, 15, 0))).as("周日").isTrue();

        // 关闭开关：高峰也立即派发
        GradingDispatchPolicy on = new GradingDispatchPolicy(true);
        GradingDispatchPolicy off = new GradingDispatchPolicy(false);
        Instant peak = beijing(2, 9, 0);
        assertThat(on.mayDispatchNow(peak)).isFalse();
        assertThat(on.nextDispatchableAt(peak)).isEqualTo(beijing(2, 12, 0));
        assertThat(off.mayDispatchNow(peak)).isTrue();
        assertThat(off.nextDispatchableAt(peak)).isEqualTo(peak);
    }

    // ================= T092：due + grace 边界与队列状态流转 =================

    @Test
    @DisplayName("T092：交卷进 QUEUED；due+grace 到点才派发；派发建一条 run 并转 PENDING")
    void dueGraceBoundaryAndQueueTransition() throws Exception {
        String classId = createClass("高一（1）班");
        String invite = classInvite(classId);
        join(student, invite);
        // due = 09:00 北京（BASE+1h），grace 3h → 派发点 12:00 北京（BASE+4h，空闲时段）
        String offeringId = createOffering(createPaper(), classId,
                BASE.plus(Duration.ofHours(1)).toString(), 3);
        String submissionId = submitAs(student, offeringId).get("submissionId").toString();

        assertThat(gradingState(submissionId)).as("交卷后等待派发").isEqualTo("QUEUED");

        // 学生不能派发（403），教师可以
        HttpResponse<String> byStudent = raw(student, "POST",
                "/api/offerings/" + offeringId + "/grading/dispatch", Map.of());
        assertThat(byStudent.statusCode()).isEqualTo(403);

        // 差 1 秒：还没到期，不派发
        CLOCK.set(BASE.plus(Duration.ofHours(4)).minusSeconds(1));
        assertThat(dispatcher.autoDispatch()).isZero();
        assertThat(gradingDispatchedAt(offeringId)).isNull();
        assertThat(gradingState(submissionId)).isEqualTo("QUEUED");

        // 正好 due + grace：派发
        CLOCK.set(BASE.plus(Duration.ofHours(4)));
        assertThat(dispatcher.autoDispatch()).isEqualTo(1);
        assertThat(gradingDispatchedAt(offeringId)).isEqualTo(BASE.plus(Duration.ofHours(4)));
        assertThat(gradingState(submissionId)).isEqualTo("PENDING");
        assertThat(runCount(offeringId)).isEqualTo(1);

        // 重复扫描不会产生第二条 run
        assertThat(dispatcher.autoDispatch()).isZero();
        assertThat(runCount(offeringId)).isEqualTo(1);

        // worker 只领 PENDING，批完变 DONE
        assertThat(worker.processBatch(10)).isEqualTo(1);
        assertThat(gradingState(submissionId)).isEqualTo("DONE");

        Map<String, Object> progress = get(teacher, "/api/offerings/" + offeringId + "/progress");
        assertThat(((Number) progress.get("total")).intValue()).isEqualTo(1);
        assertThat(((Number) progress.get("done")).longValue()).isEqualTo(1);
        assertThat(((Number) progress.get("pending")).longValue()).isZero();
    }

    // ================= T092：并发两次派发只产生一条 run =================

    @Test
    @DisplayName("T092：两个线程同时派发同一场，条件更新保证只有一个首次派发、只有一条 run")
    void concurrentDispatchProducesOneRun() throws Exception {
        String classId = createClass("高一（2）班");
        String invite = classInvite(classId);
        join(student, invite);
        String offeringId = createOffering(createPaper(), classId,
                BASE.plus(Duration.ofDays(10)).toString(), 3);

        UUID offeringUuid = UUID.fromString(offeringId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        java.util.concurrent.Callable<OfferingGradingDispatcher.DispatchResult> task = () -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return dispatcher.dispatchNow(offeringUuid, null);
        };
        try {
            Future<OfferingGradingDispatcher.DispatchResult> first = pool.submit(task);
            Future<OfferingGradingDispatcher.DispatchResult> second = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            OfferingGradingDispatcher.DispatchResult a = first.get(30, TimeUnit.SECONDS);
            OfferingGradingDispatcher.DispatchResult b = second.get(30, TimeUnit.SECONDS);

            assertThat(a.firstDispatch() ^ b.firstDispatch()).as("只有一个抢到条件更新").isTrue();
            assertThat(a.runId()).as("重复调用返回同一条 run").isEqualTo(b.runId());
            assertThat(runCount(offeringId)).isEqualTo(1);

            // 再来一次：仍然是同一条 run，不再是首次派发
            OfferingGradingDispatcher.DispatchResult again = dispatcher.dispatchNow(offeringUuid, null);
            assertThat(again.firstDispatch()).isFalse();
            assertThat(again.runId()).isEqualTo(a.runId());
            assertThat(runCount(offeringId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ================= T091：僵尸清扫 =================

    @Test
    @DisplayName("T091：固定时钟下超过阈值的 RUNNING 退回 PENDING；正在跑的不动")
    void staleSweepRecoversOnlyExpiredRunning() throws Exception {
        String classId = createClass("高一（3）班");
        String invite = classInvite(classId);
        join(student, invite);
        String offeringId = createOffering(createPaper(), classId,
                BASE.plus(Duration.ofDays(10)).toString(), 3);
        String submissionId = submitAs(student, offeringId).get("submissionId").toString();

        // 模拟 worker 领取后进程崩溃：RUNNING 且 started_at 是 6 分钟前
        jdbc.update("update submissions set grading_state = 'RUNNING', grading_started_at = ? where id = ?",
                Timestamp.from(BASE.minus(Duration.ofMinutes(6))), UUID.fromString(submissionId));
        assertThat(sweeper.sweep()).as("超过 5 分钟阈值").isEqualTo(1);
        assertThat(gradingState(submissionId)).isEqualTo("PENDING");
        assertThat(gradingStartedAt(submissionId)).isNull();

        // 正在跑（2 分钟前领取）不能被重复批
        jdbc.update("update submissions set grading_state = 'RUNNING', grading_started_at = ? where id = ?",
                Timestamp.from(BASE.minus(Duration.ofMinutes(2))), UUID.fromString(submissionId));
        assertThat(sweeper.sweep()).as("未超过阈值").isZero();
        assertThat(gradingState(submissionId)).isEqualTo("RUNNING");
    }

    // ================= T094：progress 单条聚合 =================

    @Test
    @DisplayName("T094：progress 的 total/done/running/pending/failed 与聚合结果一致")
    void progressAggregatesInOneQuery() throws Exception {
        String classId = createClass("高一（4）班");
        String invite = classInvite(classId);
        join(student, invite);
        String student2 = createStudent("student2@teachloop.local", "示例学生二");
        join(student2, invite);
        String offeringId = createOffering(createPaper(), classId,
                BASE.plus(Duration.ofDays(10)).toString(), 3);
        submitAs(student, offeringId);
        submitAs(student2, offeringId);

        // 派发前：两份都是 QUEUED，pending 必须把它们算进去，否则进度全是 0
        Map<String, Object> before = get(teacher, "/api/offerings/" + offeringId + "/progress");
        assertThat(((Number) before.get("total")).intValue()).isEqualTo(2);
        assertThat(((Number) before.get("pending")).longValue()).isEqualTo(2);
        assertThat(((Number) before.get("done")).longValue()).isZero();

        OfferingGradingDispatcher.DispatchResult result =
                dispatcher.dispatchNow(UUID.fromString(offeringId), null);
        assertThat(result.firstDispatch()).isTrue();
        assertThat(result.enqueued()).isEqualTo(2);

        assertThat(worker.processBatch(1)).isEqualTo(1);
        Map<String, Object> afterOne = get(teacher, "/api/offerings/" + offeringId + "/progress");
        assertThat(((Number) afterOne.get("done")).longValue()).isEqualTo(1);
        assertThat(((Number) afterOne.get("pending")).longValue()).isEqualTo(1);

        // 把剩下那份标成失败，验证 failed 也来自同一条聚合
        UUID remaining = jdbc.queryForObject(
                "select id from submissions where offering_id = ? and grading_state = 'PENDING'",
                UUID.class, UUID.fromString(offeringId));
        jdbc.update("update submissions set grading_state = 'FAILED', grading_error = '测试失败' where id = ?",
                remaining);

        Map<String, Object> afterFailed = get(teacher, "/api/offerings/" + offeringId + "/progress");
        assertThat(((Number) afterFailed.get("total")).intValue()).isEqualTo(2);
        assertThat(((Number) afterFailed.get("done")).longValue()).isEqualTo(1);
        assertThat(((Number) afterFailed.get("failed")).longValue()).isEqualTo(1);
        assertThat(((Number) afterFailed.get("pending")).longValue()).isZero();
        assertThat(((Number) afterFailed.get("running")).longValue()).isZero();
    }

    // ================= T093：自动派发推迟、手动派发不受限 =================

    @Test
    @DisplayName("T093：高峰时段自动派发推迟到空闲起点；手动派发随时可执行")
    void peakDefersAutomaticButNotManualDispatch() throws Exception {
        String classId = createClass("高一（5）班");
        String invite = classInvite(classId);
        join(student, invite);
        String paperId = createPaper();

        // 场次 A：due 08:00 + grace 1h = 09:00（高峰）→ 自动推迟，手动立即派发
        String manual = createOffering(paperId, classId, BASE.toString(), 1);
        CLOCK.set(BASE.plus(Duration.ofHours(1)));
        assertThat(dispatcher.autoDispatch()).isZero();
        assertThat(gradingDispatchedAt(manual)).isNull();
        OfferingGradingDispatcher.DispatchResult manualResult =
                dispatcher.dispatchNow(UUID.fromString(manual), null);
        assertThat(manualResult.firstDispatch()).isTrue();
        assertThat(gradingDispatchedAt(manual)).isNotNull();

        // 场次 B：due 08:00 + grace 3h = 11:00（仍高峰）→ 12:00 空闲起点才自动派发
        String deferred = createOffering(paperId, classId, BASE.toString(), 3);
        assertThat(dispatcher.autoDispatch()).as("09:00 高峰").isZero();
        assertThat(gradingDispatchedAt(deferred)).isNull();
        assertThat(dispatcher.autoDispatch()).as("11:00 仍高峰").isZero();
        assertThat(gradingDispatchedAt(deferred)).isNull();

        CLOCK.set(BASE.plus(Duration.ofHours(4))); // 12:00 空闲
        assertThat(dispatcher.autoDispatch()).isEqualTo(1);
        assertThat(gradingDispatchedAt(deferred)).isEqualTo(BASE.plus(Duration.ofHours(4)));
    }

    @Test
    @DisplayName("T093：关闭 peak-aware 后，高峰时段的自动派发也立即执行")
    void peakAwareOffDispatchesImmediately() throws Exception {
        String classId = createClass("高一（6）班");
        String invite = classInvite(classId);
        join(student, invite);
        // due 08:00 + grace 1h = 09:00（高峰时段）
        String offeringId = createOffering(createPaper(), classId, BASE.toString(), 1);
        CLOCK.set(BASE.plus(Duration.ofHours(1)));

        // 用 peak-aware=false 的策略直接构造一个 dispatcher：自动派发不再等待空闲时段
        OfferingGradingDispatcher noPeak = new OfferingGradingDispatcher(offeringRepository,
                submissionRepository, gradingDispatchPort, new GradingDispatchPolicy(false), auditPort, CLOCK);
        Integer dispatched = transactionTemplate.execute(status -> noPeak.autoDispatch());

        assertThat(dispatched).isEqualTo(1);
        assertThat(gradingDispatchedAt(offeringId)).isEqualTo(BASE.plus(Duration.ofHours(1)));
    }

    // ================= fixtures / helpers =================

    private Instant beijing(int dayOfMonth, int hour, int minute) {
        return LocalDateTime.of(2026, 11, dayOfMonth, hour, minute).atZone(SHANGHAI).toInstant();
    }

    private String createPaper() throws Exception {
        String paperId = post("/api/papers", Map.of("title", "T092 测试卷", "defaultDurationMinutes", 60), "id");
        String sectionId = post("/api/papers/" + paperId + "/sections",
                Map.of("title", "写作", "pointsPerItem", 20), "id");
        post("/api/papers/" + paperId + "/items", Map.of("number", 1, "questionType", "ESSAY",
                "stem", "Write a short letter.", "points", 20, "paperSectionId", sectionId), "id");
        post("/api/papers/" + paperId + "/publish", Map.of(), "id");
        return paperId;
    }

    private String createClass(String name) throws Exception {
        return post("/api/classes", Map.of("name", name), "id");
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
        send(cookie, "POST", "/api/classes/join", Map.of("inviteCode", inviteCode));
    }

    private String createOffering(String paperId, String classId, String dueAt, int graceHours)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("paperId", paperId);
        body.put("kind", "ASSIGNMENT");
        body.put("timingMode", "INDIVIDUAL");
        body.put("dueAt", dueAt);
        body.put("durationMinutes", 60);
        body.put("graceHours", graceHours);
        body.put("classes", List.of(Map.of("classId", classId)));
        String offeringId = send(teacher, "POST", "/api/offerings", body).get("id").toString();
        send(teacher, "POST", "/api/offerings/" + offeringId + "/publish", Map.of());
        return offeringId;
    }

    private Map<String, Object> submitAs(String cookie, String offeringId) throws Exception {
        send(cookie, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        return send(cookie, "POST", "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", "这是一次作业的正文内容，用于触发主观批改。"));
    }

    private String createStudent(String email, String displayName) throws Exception {
        if (users.findByEmail(email).isEmpty()) {
            users.save(new User(UUID.randomUUID(), UserRole.STUDENT, email, encoder.encode("teachloop123"),
                    displayName, Instant.now(CLOCK)));
        }
        return login(email);
    }

    private String gradingState(String submissionId) {
        return jdbc.queryForObject("select grading_state from submissions where id = ?", String.class,
                UUID.fromString(submissionId));
    }

    private Instant gradingStartedAt(String submissionId) {
        return instant("select grading_started_at from submissions where id = ?",
                UUID.fromString(submissionId));
    }

    private Instant gradingDispatchedAt(String offeringId) {
        return instant("select grading_dispatched_at from offerings where id = ?",
                UUID.fromString(offeringId));
    }

    private int runCount(String offeringId) {
        Integer count = jdbc.queryForObject("select count(*) from grading_runs where offering_id = ?",
                Integer.class, UUID.fromString(offeringId));
        return count == null ? 0 : count;
    }

    private Instant instant(String sql, Object... args) {
        Timestamp ts = jdbc.queryForObject(sql, Timestamp.class, args);
        return ts == null ? null : ts.toInstant();
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

    /** 可推进的固定时钟（不改 Phase2ExamTimingTest，单独一份）。 */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant start) {
            this.instant = new AtomicReference<>(start);
        }

        void set(Instant t) {
            instant.set(t);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
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
