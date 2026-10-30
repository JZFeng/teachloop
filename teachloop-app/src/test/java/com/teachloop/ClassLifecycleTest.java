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
 * 012-class-lifecycle 的接口级验收（T582–T588）。
 *
 * <p>与 {@link ClassAccessTest} 用同一套写法：真 HTTP + 真 Postgres（testcontainers），
 * 断言全部落在「库里还剩几行」与「接口返回什么」上，不看实现。
 *
 * <p>多班规则是这一轮最容易写错的一条：一个作业发给两个班、只结其中一个，
 * 该生**仍能提交**——判断口径是「至少一个未归档班仍包含该生」，不是「这个作业有一个班结课了」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ClassLifecycleTest {

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
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    Ports.ClassMembership membership;

    private final HttpClient client = HttpClient.newHttpClient();

    // ---------- US-96：退班 ----------

    @Test
    @DisplayName("AC-96.2/96.3：退班只写 LEFT 不删行；重入复用同一行，joined_at 不变、写 rejoined_at")
    void leaveKeepsRowAndRejoinReusesIt() throws Exception {
        String base = base();
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        Map<String, Object> clazz = createClass(base, teacher, "退班测试班");
        String classId = str(clazz.get("id"));
        joinClass(base, student, str(clazz.get("inviteCode")));

        // 一份历史作业 + 一次真实提交：退班后这些必须还在（AC-96.3「历史提交与成绩仍可查」）。
        String offeringId = publishAssignment(base, teacher, List.of(classId), "退班前的作业");
        submit(base, student, offeringId, "This is my homework before leaving the class.");
        String vocabBefore = vocabFingerprint();
        Long rowsBefore = count("select count(*) from class_members where class_id = ?", UUID.fromString(classId));
        assertThat(rowsBefore).isEqualTo(1L);
        String joinedAtBefore = str(jdbc.queryForObject(
                "select joined_at from class_members where class_id = ?", String.class, UUID.fromString(classId)));

        // 退班：204 不是它的语义，这里返回 200 + status=LEFT。
        HttpResponse<String> left = request(base, student, "DELETE", "/api/classes/" + classId + "/membership", null);
        assertThat(left.statusCode()).withFailMessage(left.body()).isEqualTo(200);
        assertThat(str(Json.parse(left.body()).get("status"))).isEqualTo("LEFT");

        // 行还在、状态是 LEFT、left_at 有值。
        assertThat(count("select count(*) from class_members where class_id = ?", UUID.fromString(classId)))
                .as("退班不能删行（AC-96.2）").isEqualTo(1L);
        Map<String, Object> row = jdbc.queryForMap(
                "select status, left_at, joined_at from class_members where class_id = ?", UUID.fromString(classId));
        assertThat(str(row.get("status"))).isEqualTo("LEFT");
        assertThat(row.get("left_at")).isNotNull();

        // 学生班级列表里消失（AC-96.3），作业列表里也消失（不再是参与者）。
        assertThat(classIds(base, student)).doesNotContain(classId);
        assertThat(offeringIds(base, student)).doesNotContain(offeringId);

        // AC-96.4：退班不改写词库进度（vocab_progress 行数与 due_at 逐行一致）。
        assertThat(vocabFingerprint()).as("退班前后 vocab_progress 必须逐行一致").isEqualTo(vocabBefore);

        // 退班后不能再提交：不再是参与者 -> 404（不泄露）。
        assertThat(request(base, student, "POST", "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", "second try")).statusCode()).isEqualTo(404);

        // 不在班时这条历史入口随班一起消失（GET /api/classes 已不返回它，AC-96.3 第一句）。
        assertThat(request(base, student, "GET", "/api/offerings/" + offeringId + "/my-submissions", null)
                .statusCode()).isEqualTo(404);

        // 重新用同一个邀请码加入：复用同一行，joined_at 不变，rejoined_at 有值（FR-217）。
        joinClass(base, student, str(clazz.get("inviteCode")));
        assertThat(count("select count(*) from class_members where class_id = ?", UUID.fromString(classId)))
                .as("重入不能新增第二行").isEqualTo(1L);
        Map<String, Object> rejoined = jdbc.queryForMap(
                "select status, rejoined_at from class_members where class_id = ?", UUID.fromString(classId));
        assertThat(str(rejoined.get("status"))).isEqualTo("ACTIVE");
        assertThat(str(jdbc.queryForObject("select joined_at from class_members where class_id = ?",
                String.class, UUID.fromString(classId))))
                .as("joined_at 永远是首次加入时间").isEqualTo(joinedAtBefore);
        assertThat(rejoined.get("rejoined_at")).isNotNull();
        assertThat(classIds(base, student)).contains(classId);

        // 重入后历史提交与成绩仍可查，attempt / 已交卷状态原样保留（AC-96.3 最后一句）。
        HttpResponse<String> mySubs = request(base, student, "GET",
                "/api/offerings/" + offeringId + "/my-submissions", null);
        assertThat(mySubs.statusCode()).withFailMessage(mySubs.body()).isEqualTo(200);
        Map<String, Object> subsBody = Json.parse(mySubs.body());
        assertThat(((Number) subsBody.get("attemptsUsed")).intValue())
                .as("重新加入后历史提交仍在").isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from submissions where offering_id = ? and submitted_at is not null",
                UUID.fromString(offeringId))).isEqualTo(1L);

        // 已在班时重复加入必须幂等：行数仍是 1，joined_at 仍不变。
        joinClass(base, student, str(clazz.get("inviteCode")));
        assertThat(count("select count(*) from class_members where class_id = ?", UUID.fromString(classId)))
                .isEqualTo(1L);
        assertThat(str(jdbc.queryForObject("select joined_at from class_members where class_id = ?",
                String.class, UUID.fromString(classId)))).isEqualTo(joinedAtBefore);

        // 审计：class:leave 落下了一条（FR-223）。
        assertThat(count("select count(*) from audit_logs where action = 'class:leave' and target_id = ?",
                classId)).isEqualTo(1L);
    }

    // ---------- US-97：老师移出 ----------

    @Test
    @DisplayName("AC-97.1/97.2：非属主 404；移出写 LEFT；成员列表默认只 ACTIVE，includeLeft=1 才含已离开")
    void teacherRemoveOnlyOwnerAndListFilters() throws Exception {
        String base = base();
        String teacher1 = login(base, "teacher@teachloop.local");
        String teacher2 = login(base, secondTeacher());
        String student = login(base, "student@teachloop.local");

        Map<String, Object> clazz = createClass(base, teacher1, "移出测试班");
        String classId = str(clazz.get("id"));
        joinClass(base, student, str(clazz.get("inviteCode")));
        String studentId = meId(base, student);

        // 先造一份已交的提交：移出后它必须还在老师的批改表里（AC-97.4）。
        String offeringId = publishAssignment(base, teacher1, List.of(classId), "移出后仍要批改的作业");
        submit(base, student, offeringId, "Submitted before the teacher removed me from the class.");

        // 非属主（另一个老师）调用：404，不泄露存在性。
        assertThat(request(base, teacher2, "DELETE", "/api/classes/" + classId + "/members/" + studentId, null)
                .statusCode()).isEqualTo(404);

        HttpResponse<String> removed = request(base, teacher1, "DELETE",
                "/api/classes/" + classId + "/members/" + studentId, null);
        assertThat(removed.statusCode()).withFailMessage(removed.body()).isEqualTo(200);
        assertThat(str(Json.parse(removed.body()).get("status"))).isEqualTo("LEFT");
        assertThat(count("select count(*) from class_members where class_id = ?",
                UUID.fromString(classId))).as("移出也不能删行").isEqualTo(1L);

        // 默认列表：空；含已离开：1 行，带 status=LEFT 与 leftAt。
        List<?> active = Json.parseList(request(base, teacher1, "GET",
                "/api/classes/" + classId + "/members", null).body());
        assertThat(active).isEmpty();
        List<?> withLeft = Json.parseList(request(base, teacher1, "GET",
                "/api/classes/" + classId + "/members?includeLeft=1", null).body());
        assertThat(withLeft).hasSize(1);
        Map<?, ?> leftRow = (Map<?, ?>) withLeft.get(0);
        assertThat(str(leftRow.get("status"))).isEqualTo("LEFT");
        assertThat(leftRow.get("leftAt")).isNotNull();

        // 班级列表的 memberCount 只数 ACTIVE（AC-97.2 / 同步统计口径）。
        Map<?, ?> classRow = findClassRow(classRows(base, teacher1), classId);
        assertThat(((Number) classRow.get("memberCount")).longValue()).isZero();

        // AC-97.4：移出后该生的提交仍在老师的批改表里，成绩不丢。
        List<?> submissionRows = Json.parseList(request(base, teacher1, "GET",
                "/api/offerings/" + offeringId + "/submissions", null).body());
        assertThat(submissionRows).as("移出不能把已交的提交从批改表里抹掉").hasSize(1);

        // 重复移出：目标已不是 ACTIVE -> 404（幂等语义，不报 500）。
        assertThat(request(base, teacher1, "DELETE",
                "/api/classes/" + classId + "/members/" + studentId, null).statusCode()).isEqualTo(404);
        assertThat(count("select count(*) from audit_logs where action = 'class:remove-member' and target_id = ?",
                classId)).isEqualTo(1L);

        // 端口口径同步（FR-216）：发词收件人只取 ACTIVE，退班学生不再出现在 membersOf 里。
        assertThat(membership.membersOf(UUID.fromString(classId))).isEmpty();
        assertThat(membership.classIdsOf(UUID.fromString(studentId))).doesNotContain(UUID.fromString(classId));
    }

    // ---------- US-98：结课 / 恢复 ----------

    @Test
    @DisplayName("AC-98.1/98.3/98.5/98.6：结课与恢复幂等；结课后邀请码 404 且不泄露；学生数据逐行不变")
    void archiveAndRestoreLifecycle() throws Exception {
        String base = base();
        String teacher = login(base, "teacher@teachloop.local");
        String teacher2 = login(base, secondTeacher());
        String student = login(base, "student@teachloop.local");

        Map<String, Object> clazz = createClass(base, teacher, "结课测试班");
        String classId = str(clazz.get("id"));
        String invite = str(clazz.get("inviteCode"));
        joinClass(base, student, invite);
        String offeringId = publishAssignment(base, teacher, List.of(classId), "结课前的作业");
        submit(base, student, offeringId, "Homework submitted before the class was archived.");

        String submissionBefore = str(jdbc.queryForObject(
                "select submitted_at from submissions where offering_id = ? order by created_at limit 1",
                String.class, UUID.fromString(offeringId)));
        String vocabBefore = vocabFingerprint();

        // 非属主不能结课。
        assertThat(request(base, teacher2, "POST", "/api/classes/" + classId + "/archive", Map.of())
                .statusCode()).isEqualTo(404);

        HttpResponse<String> archived = request(base, teacher, "POST",
                "/api/classes/" + classId + "/archive", Map.of());
        assertThat(archived.statusCode()).withFailMessage(archived.body()).isEqualTo(200);
        assertThat(Json.parse(archived.body()).get("archived")).isEqualTo(Boolean.TRUE);
        assertThat(str(jdbc.queryForObject("select archived from classes where id = ?",
                Boolean.class, UUID.fromString(classId)))).isEqualTo("true");

        // 幂等：重复结课仍是 200 + archived=true。
        HttpResponse<String> again = request(base, teacher, "POST", "/api/classes/" + classId + "/archive", Map.of());
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(Json.parse(again.body()).get("archived")).isEqualTo(Boolean.TRUE);

        // 学生仍能看到这个班，且带 archived=true（AC-98.2）。
        Map<?, ?> studentRow = findClassRow(classRows(base, student), classId);
        assertThat(studentRow.get("archived")).isEqualTo(Boolean.TRUE);

        // 结课后邀请码失效：404，且响应体不能出现「已归档 / 结课」之类存在性信息（AC-98.3）。
        HttpResponse<String> joinArchived = request(base, student, "POST", "/api/classes/join",
                Map.of("inviteCode", invite));
        assertThat(joinArchived.statusCode()).isEqualTo(404);
        assertThat(joinArchived.body()).doesNotContain("归档").doesNotContain("结课");

        // 结课不改写学生数据（AC-98.6）。
        assertThat(str(jdbc.queryForObject(
                "select submitted_at from submissions where offering_id = ? order by created_at limit 1",
                String.class, UUID.fromString(offeringId)))).isEqualTo(submissionBefore);
        assertThat(vocabFingerprint()).as("结课/恢复不改写 vocab_progress").isEqualTo(vocabBefore);

        // 恢复：archived=false、幂等、邀请码重新有效（AC-98.5）。
        HttpResponse<String> restored = request(base, teacher, "POST",
                "/api/classes/" + classId + "/restore", Map.of());
        assertThat(restored.statusCode()).withFailMessage(restored.body()).isEqualTo(200);
        assertThat(Json.parse(restored.body()).get("archived")).isEqualTo(Boolean.FALSE);
        assertThat(request(base, teacher, "POST", "/api/classes/" + classId + "/restore", Map.of())
                .statusCode()).isEqualTo(200);
        assertThat(request(base, student, "POST", "/api/classes/join", Map.of("inviteCode", invite))
                .statusCode()).as("恢复后邀请码重新可用").isEqualTo(200);

        // 审计：class:archive / class:restore 各一条（重复调用不再写）。
        assertThat(count("select count(*) from audit_logs where action = 'class:archive' and target_id = ?",
                classId)).isEqualTo(1L);
        assertThat(count("select count(*) from audit_logs where action = 'class:restore' and target_id = ?",
                classId)).isEqualTo(1L);
    }

    // ---------- 多班规则 + 建作业拦截（T585 核心） ----------

    @Test
    @DisplayName("T585 多班规则：作业发给两班、只结一个班，该生仍可提交；两个班都结才 409 CLASS_ARCHIVED")
    void multiClassArchiveKeepsSubmittable() throws Exception {
        String base = base();
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        Map<String, Object> classA = createClass(base, teacher, "多班规则 A 班");
        Map<String, Object> classB = createClass(base, teacher, "多班规则 B 班");
        String classAId = str(classA.get("id"));
        String classBId = str(classB.get("id"));
        joinClass(base, student, str(classA.get("inviteCode")));
        joinClass(base, student, str(classB.get("inviteCode")));

        String offeringId = publishAssignment(base, teacher, List.of(classAId, classBId), "跨两个班的作业");
        String offering2Id = publishAssignment(base, teacher, List.of(classAId, classBId), "第二个跨班作业");

        // 只结掉 B 班。
        assertThat(request(base, teacher, "POST", "/api/classes/" + classBId + "/archive", Map.of())
                .statusCode()).isEqualTo(200);

        // ① 该生仍可提交：A 班还开着（这就是「至少一个未归档班仍包含该生」）。
        request(base, student, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        HttpResponse<String> submitted = request(base, student, "POST",
                "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentText", "Still submittable: the other class of this assignment is open."));
        assertThat(submitted.statusCode())
                .withFailMessage("多班规则写错了：只结一个班不能锁死该生。body=" + submitted.body())
                .isEqualTo(200);

        // ② 往已结课的班新建作业：409 CLASS_ARCHIVED（AC-98.7 / FR-225）。
        HttpResponse<String> rejected = request(base, teacher, "POST", "/api/offerings", Map.of(
                "title", "不该发出去的作业", "instructions", "x", "totalPoints", 10,
                "rubricMarkdown", rubric(), "dueAt", "2027-12-31T23:59:59Z",
                "timingMode", "INDIVIDUAL", "classes", List.of(Map.of("classId", classBId)), "publish", true));
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(str(Json.parse(rejected.body()).get("code"))).isEqualTo("CLASS_ARCHIVED");

        // 把 A 班也结掉：两个参与班都归档 -> 提交必须 409 CLASS_ARCHIVED（FR-220）。
        assertThat(request(base, teacher, "POST", "/api/classes/" + classAId + "/archive", Map.of())
                .statusCode()).isEqualTo(200);
        request(base, student, "POST", "/api/offerings/" + offering2Id + "/start", Map.of());
        HttpResponse<String> blocked = request(base, student, "POST",
                "/api/offerings/" + offering2Id + "/submissions",
                Map.of("contentText", "This submission must be rejected."));
        assertThat(blocked.statusCode()).withFailMessage(blocked.body()).isEqualTo(409);
        assertThat(str(Json.parse(blocked.body()).get("code"))).isEqualTo("CLASS_ARCHIVED");

        // ③ 恢复 A 班后又能交（AC-98.5 / 多班规则不是一次性锁死）。
        assertThat(request(base, teacher, "POST", "/api/classes/" + classAId + "/restore", Map.of())
                .statusCode()).isEqualTo(200);
        request(base, student, "POST", "/api/offerings/" + offering2Id + "/start", Map.of());
        assertThat(request(base, student, "POST", "/api/offerings/" + offering2Id + "/submissions",
                Map.of("contentText", "Submittable again after the class was restored.")).statusCode())
                .isEqualTo(200);
    }

    // ---------- 退班确认数据（AC-96.5） ----------

    @Test
    @DisplayName("AC-96.5：退班前的 leave-preview 给出未交作业数（已交的不算）")
    void leavePreviewCountsPendingAssignments() throws Exception {
        String base = base();
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        Map<String, Object> clazz = createClass(base, teacher, "退班预览测试班");
        String classId = str(clazz.get("id"));
        joinClass(base, student, str(clazz.get("inviteCode")));
        String doneOffering = publishAssignment(base, teacher, List.of(classId), "已交的作业");
        publishAssignment(base, teacher, List.of(classId), "未交的作业");

        submit(base, student, doneOffering, "Handed in on time.");

        HttpResponse<String> preview = request(base, student, "GET",
                "/api/classes/" + classId + "/leave-preview", null);
        assertThat(preview.statusCode()).withFailMessage(preview.body()).isEqualTo(200);
        Map<String, Object> body = Json.parse(preview.body());
        assertThat(((Number) body.get("totalAssignments")).longValue()).isEqualTo(2L);
        assertThat(((Number) body.get("submittedAssignments")).longValue()).isEqualTo(1L);
        assertThat(((Number) body.get("pendingAssignments")).longValue())
                .as("未交作业数 = 发布中的布置 - 已交卷的布置").isEqualTo(1L);
        assertThat(body.get("archived")).isEqualTo(Boolean.FALSE);
    }

    // ---------- helpers ----------

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    /** 复用/创建测试用的二号老师（与 ClassAccessTest 同名不同班，互不影响）。 */
    private String secondTeacher() {
        if (users.findByEmail("teacher2@teachloop.local").isEmpty()) {
            users.save(new User(UUID.randomUUID(), UserRole.TEACHER, "teacher2@teachloop.local",
                    encoder.encode("teachloop123"), "二号老师", Instant.now()));
        }
        return "teacher2@teachloop.local";
    }

    private Map<String, Object> createClass(String base, String teacher, String name) throws Exception {
        HttpResponse<String> res = request(base, teacher, "POST", "/api/classes", Map.of("name", name));
        assertThat(res.statusCode()).withFailMessage(res.body()).isEqualTo(200);
        return Json.parse(res.body());
    }

    private void joinClass(String base, String student, String inviteCode) throws Exception {
        HttpResponse<String> res = request(base, student, "POST", "/api/classes/join",
                Map.of("inviteCode", inviteCode));
        assertThat(res.statusCode()).withFailMessage(res.body()).isEqualTo(200);
    }

    private String publishAssignment(String base, String teacher, List<String> classIds, String title)
            throws Exception {
        List<Map<String, Object>> classes = classIds.stream()
                .map(id -> Map.<String, Object>of("classId", id)).toList();
        HttpResponse<String> res = request(base, teacher, "POST", "/api/offerings", Map.of(
                "title", title,
                "instructions", "Write two paragraphs about your favourite season.",
                "totalPoints", 10,
                "rubricMarkdown", rubric(),
                "dueAt", "2027-12-31T23:59:59Z",
                "timingMode", "INDIVIDUAL",
                "classes", classes,
                "publish", true));
        assertThat(res.statusCode()).withFailMessage(res.body()).isEqualTo(200);
        Map<String, Object> offering = Json.parse(res.body());
        assertThat(offering.get("status")).isEqualTo("PUBLISHED");
        return str(offering.get("id"));
    }

    private static String rubric() {
        return "# Scoring Rubric\n\n"
                + "### 1. Content — 5 points\n是否切题且给出理由。\n- 是：5 分\n- 否：0 分\n\n"
                + "### 2. Language — 5 points\n语法与用词是否正确。\n- 是：5 分\n- 否：0 分\n";
    }

    private void submit(String base, String student, String offeringId, String text) throws Exception {
        request(base, student, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        HttpResponse<String> res = request(base, student, "POST",
                "/api/offerings/" + offeringId + "/submissions", Map.of("contentText", text));
        assertThat(res.statusCode()).withFailMessage(res.body()).isEqualTo(200);
    }

    private static Map<?, ?> findClassRow(List<?> rows, String classId) {
        for (Object row : rows) {
            Map<?, ?> map = (Map<?, ?>) row;
            if (classId.equals(str(map.get("id")))) {
                return map;
            }
        }
        throw new AssertionError("class not found in list: " + classId);
    }

    private List<?> classRows(String base, String cookie) throws Exception {
        return Json.parseList(request(base, cookie, "GET", "/api/classes", null).body());
    }

    private List<String> classIds(String base, String cookie) throws Exception {
        return classRows(base, cookie).stream().map(row -> str(((Map<?, ?>) row).get("id"))).toList();
    }

    private List<String> offeringIds(String base, String cookie) throws Exception {
        return Json.parseList(request(base, cookie, "GET", "/api/offerings", null).body()).stream()
                .map(row -> str(((Map<?, ?>) row).get("id"))).toList();
    }

    private String meId(String base, String cookie) throws Exception {
        HttpResponse<String> res = request(base, cookie, "GET", "/api/auth/me", null);
        return str(Json.parse(res.body()).get("id"));
    }

    /** AC-96.4/98.6：词库进度的逐行指纹（行数 + 每行 id/词 id/due_at），用来断言「没有被动过」。 */
    private String vocabFingerprint() {
        Long rows = count("select count(*) from vocab_progress");
        String digest = jdbc.queryForObject(
                "select coalesce(md5(string_agg(word_id::text || '|' || coalesce(due_at::text, ''), ',' "
                        + "order by word_id)), 'empty') from vocab_progress",
                String.class);
        return rows + ":" + digest;
    }

    private Long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String login(String base, String email) throws Exception {
        HttpResponse<String> res = request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(res.statusCode()).withFailMessage(res.body()).isEqualTo(200);
        return res.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
    }

    private HttpResponse<String> request(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (cookie != null) b.header("Cookie", cookie);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static String write(Object o) { return MAPPER.writeValueAsString(o); }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) { return MAPPER.readValue(s, Map.class); }

        static List<?> parseList(String s) { return MAPPER.readValue(s, List.class); }
    }
}
