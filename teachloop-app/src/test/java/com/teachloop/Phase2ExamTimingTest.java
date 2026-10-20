package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.sitting.service.ExamDeadlineJob;
import com.teachloop.sitting.service.ExamSessionService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 阶段二（T198–T202）验收：内容快照、按班覆盖、服务端权威计时与到点自动交卷、
 * 考前须知、一场挂多班、迟交与拒收。
 *
 * <p>用可推进的固定时钟，保证「改本机时间」「到点」这些行为可以确定性地断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(Phase2ExamTimingTest.ClockOverride.class)
class Phase2ExamTimingTest {

    /** 2026-11-02 09:00（北京时间，周一）。 */
    private static final Instant BASE = Instant.parse("2026-11-02T01:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE);

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // 关闭后台扫描，测试里显式调用 scan()
        r.add("teachloop.exam.scan-ms", () -> "3600000");
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
    ExamDeadlineJob deadlineJob;

    @Autowired
    ExamSessionService sessions;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void resetClock() {
        CLOCK.set(BASE);
    }

    // ================= T198 / T199 / T200 / T201 =================

    @Test
    @DisplayName("T198+T199+T200+T201：快照版本、按班覆盖、服务端计时、统一开考与到点自动交卷")
    void snapshotOverridesAndServerTiming() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        String paperId = createPaper(base, teacher);
        String c1 = createClass(base, teacher, "高一（1）班");
        String c2 = createClass(base, teacher, "高一（2）班");
        String c3 = createClass(base, teacher, "高一（3）班");
        String invite1 = classInvite(base, teacher, c1);
        String invite2 = classInvite(base, teacher, c2);
        String invite3 = classInvite(base, teacher, c3);
        join(base, student, invite1);
        join(base, student, invite2);
        join(base, student, invite3);

        // T201：勾 3 个班 → 1 条 offering + 3 条 offering_classes；按班覆盖只影响该班
        Map<String, Object> create = new HashMap<>();
        create.put("paperId", paperId);
        create.put("kind", "EXAM");
        create.put("timingMode", "INDIVIDUAL");
        create.put("dueAt", BASE.plus(Duration.ofDays(10)).toString());
        create.put("durationMinutes", 60);
        create.put("classes", List.of(
                Map.of("classId", c1, "dueAtOverride", BASE.plus(Duration.ofDays(2)).toString(),
                        "durationOverride", 30),
                Map.of("classId", c2),
                Map.of("classId", c3)));
        String offeringId = send(base, teacher, "POST", "/api/offerings", create).get("id").toString();
        send(base, teacher, "POST", "/api/offerings/" + offeringId + "/publish", Map.of());

        Map<String, Object> detail = get(base, teacher, "/api/offerings/" + offeringId);
        assertThat(((Number) detail.get("classCount")).intValue()).isEqualTo(3);
        assertThat((List<?>) detail.get("classes")).hasSize(3);
        assertThat(((Number) detail.get("paperVersion")).intValue()).isEqualTo(1);
        assertThat(((Number) detail.get("effectiveDurationMinutes")).intValue()).isEqualTo(60);
        assertThat(((Number) detail.get("submissionCount")).intValue()).isZero();

        // T201：发布时通知参与者（当前实现写审计痕迹，T208 建表后替换投递）
        Integer notified = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'notification:offering_published' and target_id = ?",
                Integer.class, offeringId);
        assertThat(notified).isPositive();

        // T198：同一份试卷布置 3 次，papers 仍然只有 1 行
        Map<String, Object> second = new HashMap<>(create);
        send(base, teacher, "POST", "/api/offerings", second);
        send(base, teacher, "POST", "/api/offerings", second);
        // AC-26.3：不传 durationMinutes 时继承试卷默认时长
        Map<String, Object> inherited = new HashMap<>(create);
        inherited.remove("durationMinutes");
        Map<String, Object> inheritedView = send(base, teacher, "POST", "/api/offerings", inherited);
        assertThat(((Number) inheritedView.get("effectiveDurationMinutes")).intValue()).isEqualTo(60);
        Integer paperRows = jdbc.queryForObject("select count(*) from papers where id = ?", Integer.class,
                UUID.fromString(paperId));
        assertThat(paperRows).isEqualTo(1);

        // T200：考前须知先于作答；按班覆盖决定时长/截止（c1 覆盖为 30 分钟、2 天后截止）
        Map<String, Object> entry = get(base, student, "/api/offerings/" + offeringId + "/entry");
        assertThat(((Number) entry.get("itemCount")).intValue()).isEqualTo(2);
        assertThat(((Number) entry.get("totalPoints")).intValue()).isEqualTo(20);
        assertThat(((Number) entry.get("durationMinutes")).intValue()).isEqualTo(30);
        assertThat(entry.get("dueAt").toString()).isEqualTo(BASE.plus(Duration.ofDays(2)).toString());
        assertThat(entry.get("canStart")).isEqualTo(true);
        assertThat(entry.get("started")).isEqualTo(false);
        assertThat(((Number) entry.get("remainingSeconds")).longValue()).isEqualTo(1800);
        assertThat((List<?>) entry.get("notes")).isNotEmpty();
        assertThat(entry.get("notice").toString()).contains("个人计时");

        // 按班覆盖只影响该班：只在 c2 的学生仍看到布置级默认（60 分钟、10 天后截止）
        String student3 = createStudent(base, "student3@teachloop.local", "示例学生三");
        join(base, student3, invite2);
        Map<String, Object> other = get(base, student3, "/api/offerings/" + offeringId + "/entry");
        assertThat(((Number) other.get("durationMinutes")).intValue()).isEqualTo(60);
        assertThat(other.get("dueAt").toString())
                .isEqualTo(BASE.plus(Duration.ofDays(10)).toString());

        // 学生端试卷内容来自快照：有 section 结构，但没有标准答案（AC-21.9）
        Map<String, Object> paperView = get(base, student, "/api/offerings/" + offeringId + "/paper");
        List<?> paperItems = (List<?>) paperView.get("items");
        assertThat(paperItems).hasSize(2);
        Map<?, ?> firstItem = (Map<?, ?>) paperItems.get(0);
        assertThat(firstItem.get("sectionTitle").toString()).isEqualTo("单项选择");
        assertThat(firstItem.containsKey("answerKey")).isFalse();
        assertThat(firstItem.containsKey("answer_key")).isFalse();

        // T199：点「开始答题」才起算；剩余时间由服务端算
        Map<String, Object> started = send(base, student, "POST",
                "/api/offerings/" + offeringId + "/start", Map.of());
        assertThat(started.get("deadlineAt").toString())
                .isEqualTo(BASE.plus(Duration.ofMinutes(30)).toString());
        CLOCK.advance(Duration.ofMinutes(5));
        Map<String, Object> session = get(base, student, "/api/offerings/" + offeringId + "/session");
        assertThat(((Number) session.get("remainingSeconds")).longValue()).isEqualTo(1500);
        assertThat(session.get("deadlineAt").toString())
                .isEqualTo(BASE.plus(Duration.ofMinutes(30)).toString());

        // 换设备重连：新会话拿到的剩余时间与原来一致，不重置（AC-26.7）
        String secondDevice = login(base, "student@teachloop.local");
        Map<String, Object> reconnect = get(base, secondDevice, "/api/offerings/" + offeringId + "/session");
        assertThat(reconnect.get("remainingSeconds")).isEqualTo(session.get("remainingSeconds"));
        assertThat(reconnect.get("deadlineAt")).isEqualTo(session.get("deadlineAt"));

        // 教师端逐份提交表带显示名（经 StudentDirectory 端口，不跨模块查 users）
        List<?> table = Json.parseList(raw(base, teacher, "GET",
                "/api/offerings/" + offeringId + "/submissions", null).body());
        assertThat(table).hasSize(1);
        assertThat(((Map<?, ?>) table.get(0)).get("studentName").toString()).isEqualTo("示例学生");

        // T111/T194：反馈发布前，学生只看到自己的分，不返回标准答案
        Map<String, Object> unpublished = get(base, student,
                "/api/offerings/" + offeringId + "/my-feedback");
        assertThat(unpublished.get("feedbackPublished")).isEqualTo(false);
        assertThat((List<?>) unpublished.get("wrongItems")).isEmpty();
        assertThat(unpublished.get("feedbackMessage").toString()).contains("尚未发布");

        // T198：布置后改标准答案，已布置场次仍按快照判分
        jdbc.update("update objective_items set answer_key = 'C' where paper_id = ?", UUID.fromString(paperId));
        Map<String, Object> submitted = send(base, student, "POST",
                "/api/offerings/" + offeringId + "/submissions",
                Map.of("answers", List.of(Map.of("number", 1, "answer", "A"),
                        Map.of("number", 2, "answer", "C"))));
        // 快照里题 2 的答案是 B；库里已被改成 C。学生选 C，按快照必须判错（10 分而不是 20）
        assertThat(new BigDecimal(submitted.get("objectiveScore").toString()))
                .isEqualByComparingTo(new BigDecimal("10"));
        send(base, teacher, "POST", "/api/offerings/" + offeringId + "/scores/publish", Map.of());
        Map<String, Object> feedback = get(base, student, "/api/offerings/" + offeringId + "/my-feedback");
        assertThat(feedback.get("feedbackPublished")).isEqualTo(true);
        List<?> wrongItems = (List<?>) feedback.get("wrongItems");
        assertThat(wrongItems).hasSize(1); // 题 2 选错；题 1 答对
        Map<?, ?> wrong2 = (Map<?, ?>) wrongItems.get(0);
        assertThat(((Number) wrong2.get("number")).intValue()).isEqualTo(2);
        assertThat(wrong2.get("correctAnswer").toString()).isEqualTo("B"); // 仍是快照里的答案
        Map<?, ?> overview = (Map<?, ?>) feedback.get("overview");
        assertThat(((Number) overview.get("expected")).longValue()).isEqualTo(2);
        assertThat(new BigDecimal(overview.get("myScore").toString())).isEqualByComparingTo("10");
        assertThat(overview.get("classAverage")).isNotNull();

        Map<String, Object> attempts = get(base, student, "/api/offerings/" + offeringId + "/my-submissions");
        List<?> attemptRows = (List<?>) attempts.get("attempts");
        assertThat(new BigDecimal(((Map<?, ?>) attemptRows.get(0)).get("objectiveScore").toString()))
                .isEqualByComparingTo(new BigDecimal("10"));

        // T199 + T200：统一开考（fixed_window）——没有开始按钮，全班同一 deadline
        String student2 = createStudent(base, "student2@teachloop.local", "示例学生二");
        join(base, student2, invite1);

        Map<String, Object> fixed = new HashMap<>();
        fixed.put("paperId", paperId);
        fixed.put("kind", "EXAM");
        fixed.put("timingMode", "FIXED_WINDOW");
        fixed.put("startAt", BASE.plus(Duration.ofHours(1)).toString());
        fixed.put("dueAt", BASE.plus(Duration.ofHours(3)).toString());
        fixed.put("durationMinutes", 60);
        fixed.put("classes", List.of(Map.of("classId", c1)));
        String fixedId = send(base, teacher, "POST", "/api/offerings", fixed).get("id").toString();
        send(base, teacher, "POST", "/api/offerings/" + fixedId + "/publish", Map.of());

        Map<String, Object> beforeStart = get(base, student, "/api/offerings/" + fixedId + "/entry");
        assertThat(beforeStart.get("started")).isEqualTo(false);
        assertThat(beforeStart.get("canStart")).isEqualTo(false);
        assertThat(beforeStart.get("notice").toString()).contains("统一开考");

        CLOCK.advance(Duration.ofHours(1)); // 10:00，统一开考
        Map<String, Object> e1 = get(base, student, "/api/offerings/" + fixedId + "/entry");
        Map<String, Object> e2 = get(base, student2, "/api/offerings/" + fixedId + "/entry");
        assertThat(e1.get("started")).isEqualTo(true);
        assertThat(e1.get("canStart")).isEqualTo(false);
        assertThat(e1.get("deadlineAt")).isEqualTo(e2.get("deadlineAt"));
        assertThat(e1.get("deadlineAt").toString())
                .isEqualTo(BASE.plus(Duration.ofHours(2)).toString());
        assertThat(e1.get("notice").toString()).contains("已统一开考").contains("11:00");

        Map<String, Object> fixedStart = send(base, student, "POST",
                "/api/offerings/" + fixedId + "/start", Map.of());
        assertThat(fixedStart.get("deadlineAt").toString())
                .isEqualTo(BASE.plus(Duration.ofHours(2)).toString());

        // 自动保存之后到点：以最后一次自动保存为准，标 auto_submitted（不依赖学生点提交）
        CLOCK.advance(Duration.ofMinutes(30));
        send(base, student, "PUT", "/api/offerings/" + fixedId + "/answers",
                Map.of("answers", List.of(Map.of("number", 1, "answer", "A"))));
        CLOCK.advance(Duration.ofMinutes(31));
        deadlineJob.scan();
        Map<String, Object> afterDeadline = get(base, student, "/api/offerings/" + fixedId + "/session");
        assertThat(afterDeadline.get("locked")).isEqualTo(true);
        Map<String, Object> fixedAttempts = get(base, student,
                "/api/offerings/" + fixedId + "/my-submissions");
        Map<?, ?> fixedAttempt = (Map<?, ?>) ((List<?>) fixedAttempts.get("attempts")).get(0);
        assertThat(fixedAttempt.get("autoSubmitted")).isEqualTo(true);
        assertThat(fixedAttempt.get("submittedAt")).isNotNull();
        assertThat(fixedAttempt.get("lateBySeconds").toString()).isEqualTo("0");

        // 幂等：再扫一次不会重复定稿
        assertThat(sessions.autoSubmitExpired()).isZero();
        Long auditCount = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'submission:auto_submit'", Long.class);
        assertThat(auditCount).isNotNull().isPositive();
    }

    @Test
    @DisplayName("T199：到点后自动保存被拒绝，但定稿必须立即落库（不依赖下一次扫描）")
    void saveAfterDeadlineFinalizesImmediately() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        String paperId = createPaper(base, teacher);
        String classId = createClass(base, teacher, "高三（1）班");
        String invite = classInvite(base, teacher, classId);
        join(base, student, invite);

        Map<String, Object> create = new HashMap<>();
        create.put("paperId", paperId);
        create.put("kind", "EXAM");
        create.put("timingMode", "INDIVIDUAL");
        create.put("dueAt", BASE.plus(Duration.ofDays(1)).toString());
        create.put("durationMinutes", 60);
        create.put("classes", List.of(Map.of("classId", classId)));
        String offeringId = send(base, teacher, "POST", "/api/offerings", create).get("id").toString();
        send(base, teacher, "POST", "/api/offerings/" + offeringId + "/publish", Map.of());

        send(base, student, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        CLOCK.advance(Duration.ofMinutes(61));

        HttpResponse<String> rejected = raw(base, student, "PUT",
                "/api/offerings/" + offeringId + "/answers",
                Map.of("answers", List.of(Map.of("number", 1, "answer", "A"))));
        assertThat(rejected.statusCode()).isEqualTo(409);

        Map<String, Object> attempts = get(base, student,
                "/api/offerings/" + offeringId + "/my-submissions");
        Map<?, ?> attempt = (Map<?, ?>) ((List<?>) attempts.get("attempts")).get(0);
        assertThat(attempt.get("autoSubmitted")).isEqualTo(true);
        assertThat(attempt.get("submittedAt")).isNotNull();
    }

    // ================= T202 =================

    @Test
    @DisplayName("T202：过截止仍可提交并标迟交；教师拒收带理由；学生可见并可重交；按班汇总分开统计")
    void lateSubmissionRejectAndRetry() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        String paperId = createPaper(base, teacher);
        String classId = createClass(base, teacher, "高二（1）班");
        String invite = classInvite(base, teacher, classId);
        join(base, student, invite);

        Map<String, Object> create = new HashMap<>();
        create.put("paperId", paperId);
        create.put("kind", "ASSIGNMENT");
        create.put("timingMode", "INDIVIDUAL");
        create.put("dueAt", BASE.toString());
        create.put("durationMinutes", 60);
        create.put("graceHours", 3);
        create.put("classes", List.of(Map.of("classId", classId)));
        String offeringId = send(base, teacher, "POST", "/api/offerings", create).get("id").toString();
        send(base, teacher, "POST", "/api/offerings/" + offeringId + "/publish", Map.of());

        // 过截止 1 小时仍可提交（宽限期只决定批改启动，不拒绝提交）
        CLOCK.advance(Duration.ofHours(1));
        send(base, student, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        Map<String, Object> submit = send(base, student, "POST",
                "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", "补交的作业内容"));
        assertThat(((Number) submit.get("lateBySeconds")).longValue()).isEqualTo(3600);

        Map<String, Object> mine = get(base, student, "/api/offerings/" + offeringId + "/my-submissions");
        Map<?, ?> firstAttempt = (Map<?, ?>) ((List<?>) mine.get("attempts")).get(0);
        assertThat(((Number) firstAttempt.get("lateBySeconds")).longValue()).isEqualTo(3600);
        assertThat(mine.get("canRetry")).isEqualTo(false); // 已交卷未拒收：不能直接重交
        assertThat(firstAttempt.get("rejectedAt")).isNull();

        // 按班汇总：按时 0 / 迟交 1
        List<?> summary = getList(base, teacher, "/api/offerings/" + offeringId + "/class-summary");
        Map<?, ?> cls = (Map<?, ?>) summary.get(0);
        assertThat(((Number) cls.get("expected")).longValue()).isEqualTo(1);
        assertThat(((Number) cls.get("submitted")).longValue()).isEqualTo(1);
        assertThat(((Number) cls.get("onTime")).longValue()).isZero();
        assertThat(((Number) cls.get("late")).longValue()).isEqualTo(1);
        assertThat(new BigDecimal(cls.get("onTimeRate").toString())).isEqualByComparingTo("0.00");
        assertThat(cls.get("averageScore")).isNull();
        // T191：未交名单 / 分数分布 / 逐题正确率
        assertThat((List<?>) cls.get("unsubmitted")).isEmpty();
        List<?> dist = (List<?>) cls.get("distribution");
        assertThat(dist).hasSize(5);
        long distTotal = dist.stream().mapToLong(b -> ((Number) ((Map<?, ?>) b).get("count")).longValue()).sum();
        assertThat(distTotal).isEqualTo(1);
        List<?> accuracy = (List<?>) cls.get("itemAccuracy");
        assertThat(accuracy).hasSize(2);
        assertThat(((Number) ((Map<?, ?>) accuracy.get(0)).get("answered")).longValue()).isZero();
        assertThat(((Map<?, ?>) accuracy.get(0)).get("correctRate")).isNull();

        String submissionIdFromDb = jdbc.queryForObject(
                "select id::text from submissions where offering_id = ? order by attempt_no limit 1",
                String.class, UUID.fromString(offeringId));

        // 拒收必须给理由；学生不能拒收
        HttpResponse<String> noReason = raw(base, teacher, "POST",
                "/api/offerings/" + offeringId + "/submissions/" + submissionIdFromDb + "/reject",
                Map.of("reason", " "));
        assertThat(noReason.statusCode()).isEqualTo(400);
        HttpResponse<String> byStudent = raw(base, student, "POST",
                "/api/offerings/" + offeringId + "/submissions/" + submissionIdFromDb + "/reject",
                Map.of("reason", "我自己拒收"));
        assertThat(byStudent.statusCode()).isEqualTo(403);

        send(base, teacher, "POST",
                "/api/offerings/" + offeringId + "/submissions/" + submissionIdFromDb + "/reject",
                Map.of("reason", "迟交且未说明原因，请重交"));
        Map<String, Object> afterReject = get(base, student,
                "/api/offerings/" + offeringId + "/my-submissions");
        Map<?, ?> rejected = (Map<?, ?>) ((List<?>) afterReject.get("attempts")).get(0);
        assertThat(rejected.get("rejectedAt")).isNotNull();
        assertThat(rejected.get("rejectReason").toString()).contains("请重交");
        assertThat(afterReject.get("canRetry")).isEqualTo(true);

        // 拒收后仍有尝试次数 → 可重交（新 attempt）
        Map<String, Object> restarted = send(base, student, "POST",
                "/api/offerings/" + offeringId + "/start", Map.of());
        assertThat(((Number) restarted.get("attemptNo")).intValue()).isEqualTo(2);
        send(base, student, "POST", "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", "重交的作业内容"));
        Map<String, Object> afterRetry = get(base, student,
                "/api/offerings/" + offeringId + "/my-submissions");
        assertThat((List<?>) afterRetry.get("attempts")).hasSize(2);
        assertThat(((Number) afterRetry.get("attemptsUsed")).intValue()).isEqualTo(2);

        // 汇总里拒收份数单列，且只按每个学生的最新一次作答统计
        List<?> summary2 = getList(base, teacher, "/api/offerings/" + offeringId + "/class-summary");
        Map<?, ?> cls2 = (Map<?, ?>) summary2.get(0);
        assertThat(((Number) cls2.get("submitted")).longValue()).isEqualTo(1);
        assertThat(((Number) cls2.get("late")).longValue()).isEqualTo(1);
        assertThat(((Number) cls2.get("rejected")).longValue()).isEqualTo(1);
    }

    // ================= fixtures / helpers =================

    private String createPaper(String base, String teacher) throws Exception {
        String paperId = post(base, teacher, "/api/papers",
                Map.of("title", "阶段二统考", "defaultDurationMinutes", 60), "id");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "单项选择", "pointsPerItem", 10), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "SINGLE_CHOICE", "stem", "Question one?", "answerKey", "A",
                "points", 10, "paperSectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 2,
                "questionType", "SINGLE_CHOICE", "stem", "Question two?", "answerKey", "B",
                "points", 10, "paperSectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/publish", Map.of(), "id");
        return paperId;
    }

    private String createClass(String base, String teacher, String name) throws Exception {
        Map<String, Object> cls = send(base, teacher, "POST", "/api/classes", Map.of("name", name));
        return cls.get("id").toString();
    }

    private String classInvite(String base, String teacher, String classId) throws Exception {
        HttpResponse<String> res = raw(base, teacher, "GET", "/api/classes", null);
        assertThat(res.statusCode()).isEqualTo(200);
        List<?> classes = Json.parseList(res.body());
        for (Object o : classes) {
            Map<?, ?> c = (Map<?, ?>) o;
            if (classId.equals(c.get("id"))) {
                return c.get("inviteCode").toString();
            }
        }
        throw new AssertionError("找不到班级 " + classId);
    }

    private void join(String base, String cookie, String inviteCode) throws Exception {
        send(base, cookie, "POST", "/api/classes/join", Map.of("inviteCode", inviteCode));
    }

    private String createStudent(String base, String email, String displayName) throws Exception {
        if (users.findByEmail(email).isEmpty()) {
            users.save(new User(UUID.randomUUID(), UserRole.STUDENT, email, encoder.encode("teachloop123"),
                    displayName, Instant.now(CLOCK)));
        }
        return login(base, email);
    }

    private String login(String base, String email) throws Exception {
        HttpResponse<String> res = raw(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(res.statusCode()).as("login %s: %s", email, res.body()).isEqualTo(200);
        List<String> cookies = res.headers().allValues("set-cookie");
        return cookies.stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("登录没有下发会话 cookie：" + cookies));
    }

    private Map<String, Object> get(String base, String cookie, String path) throws Exception {
        return send(base, cookie, "GET", path, null);
    }

    private List<?> getList(String base, String cookie, String path) throws Exception {
        HttpResponse<String> res = raw(base, cookie, "GET", path, null);
        assertThat(res.statusCode()).withFailMessage("GET %s -> %s %s", path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parseList(res.body());
    }

    private String post(String base, String cookie, String path, Object body, String field) throws Exception {
        Object v = send(base, cookie, "POST", path, body).get(field);
        assertThat(v).as("%s 返回缺少字段 %s", path, field).isNotNull();
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpResponse<String> res = raw(base, cookie, method, path, body);
        assertThat(res.statusCode()).withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private HttpResponse<String> raw(String base, String cookie, String method, String path, Object body)
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

    /** 可推进的固定时钟：考试计时必须能确定性断言。 */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant start) {
            this.instant = new AtomicReference<>(start);
        }

        void set(Instant t) {
            instant.set(t);
        }

        void advance(Duration d) {
            instant.updateAndGet(current -> current.plus(d));
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
