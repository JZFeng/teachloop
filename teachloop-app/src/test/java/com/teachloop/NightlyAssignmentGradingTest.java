package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teachloop.grading.service.GradingWorker;
import com.teachloop.sitting.domain.OfferingRepository;
import com.teachloop.sitting.service.AssignmentGradingJob;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
 * 决策 003 / T330–T333：作业走夜间批量派发，考试仍按「截止 + 宽限」。
 *
 * <p>覆盖四条：
 * <ol>
 *   <li>作业的截止时间在未来（甚至已经过了），夜跑照样把排队提交推给 AI —— 作业不等 due；</li>
 *   <li>夜跑可以重复执行：不会多建 run、不会把已批完的重新排队；</li>
 *   <li>考试不在夜跑范围；到期扫描只挑 EXAM；</li>
 *   <li>学生重交后，复核队列只保留最新一版（旧 attempt 的复核行不进队列）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class NightlyAssignmentGradingTest {

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
        // 测试里手动触发夜跑，不让启动补跑抢先
        r.add("teachloop.grading.assignment-catch-up", () -> "false");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AssignmentGradingJob job;

    @Autowired
    OfferingRepository offerings;

    @Autowired
    GradingWorker worker;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("作业不等截止：夜跑把排队提交推给 AI；重复跑幂等；考试不在夜跑范围；队列只留最新 attempt")
    void nightlyGradingIgnoresDueAndIsIdempotent() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");

        String classId = post(base, teacher, "/api/classes", Map.of("name", "夜间批改班"), "id");
        // 邀请码从班级列表里取（与前端一致）
        String code = String.valueOf(getList(base, teacher, "/api/classes").stream()
                .map(o -> (Map<?, ?>) o)
                .filter(c -> classId.equals(c.get("id")))
                .findFirst().orElseThrow().get("inviteCode"));
        post(base, student, "/api/classes/join", Map.of("inviteCode", code), "name");

        // 截止时间设在过去 + 零宽限：如果夜跑依赖 due，这里根本不会发生派发。
        String offeringId = post(base, teacher, "/api/offerings", Map.of(
                "title", "夜间批改作业",
                "instructions", "写一段话介绍你的城市。",
                "totalPoints", 10,
                "rubricMarkdown", "# Scoring Rubric\n\n### 1. Content — 5 points\n- Yes: 5 points\n\n"
                        + "### 2. Language — 5 points\n- Yes: 5 points\n",
                "dueAt", Instant.now().minus(1, ChronoUnit.HOURS).toString(),
                "graceHours", 0,
                "maxAttempts", 3,
                "timingMode", "INDIVIDUAL",
                "classes", List.of(Map.of("classId", classId)),
                "publish", true), "id");

        submit(base, student, offeringId, "My city is small but lively.");
        assertThat(submissionState(offeringId)).isEqualTo("QUEUED");

        // ① 到期扫描只挑考试：这份作业即使过了截止 + 宽限，也不该被它抓到。
        assertThat(offerings.findDueForGrading(Instant.now())).doesNotContain(java.util.UUID.fromString(offeringId));

        // ② 夜跑：QUEUED → PENDING（此时还没跑 AI），run 只有一条。
        job.nightly();
        assertThat(submissionState(offeringId)).isEqualTo("PENDING");
        assertThat(runCount(offeringId)).isEqualTo(1);

        worker.processBatch(10);
        assertThat(submissionState(offeringId)).isEqualTo("DONE");
        assertThat(reviewCount(offeringId)).isEqualTo(1);
        // T335：这一场跑完了，run 收尾（state=done + finished_at 落库）
        assertThat(runState(offeringId)).isEqualTo("done");
        assertThat(runFinishedAt(offeringId)).isNotNull();

        // ③ 再跑一次夜跑：已经批完的不再入队，run 也不会多。
        job.nightly();
        assertThat(submissionState(offeringId)).isEqualTo("DONE");
        assertThat(runCount(offeringId)).isEqualTo(1);

        // ④ 学生重交：旧 attempt 变 inactive，新提交当夜再批；队列里只留最新那一版。
        resubmit(base, student, offeringId, "My city is small but lively, and the food is great.");
        assertThat(pendingSubmissions(offeringId)).isEqualTo(1);
        job.nightly();
        // T335：有新工作进队，已收尾的 run 重新打开
        assertThat(runState(offeringId)).isEqualTo("running");
        worker.processBatch(10);
        assertThat(runState(offeringId)).isEqualTo("done");

        List<String> queueSubmissionIds = getList(base, teacher, "/api/grading/queue").stream()
                .map(o -> String.valueOf(((Map<?, ?>) o).get("submissionId")))
                .toList();
        assertThat(queueSubmissionIds).as("重交后队列只应出现最新一次提交")
                .hasSize(1)
                .containsExactly(activeSubmissionId(offeringId));
    }

    // ---------------------------------------------------------------- helpers

    private void submit(String base, String student, String offeringId, String text) throws Exception {
        HttpResponse<String> started = request(base, student, "POST",
                "/api/offerings/" + offeringId + "/start", Map.of());
        assertThat(started.statusCode()).withFailMessage("start: " + started.body()).isEqualTo(200);
        HttpResponse<String> submitted = request(base, student, "POST",
                "/api/offerings/" + offeringId + "/submissions", Map.of("contentText", text));
        assertThat(submitted.statusCode()).withFailMessage("submit: " + submitted.body()).isEqualTo(200);
    }

    /** 重交要显式开新 attempt（T064）；旧 attempt 会被置为非 active。 */
    private void resubmit(String base, String student, String offeringId, String text) throws Exception {
        HttpResponse<String> attempt = request(base, student, "POST",
                "/api/offerings/" + offeringId + "/attempts", Map.of());
        assertThat(attempt.statusCode()).withFailMessage("new attempt: " + attempt.body()).isEqualTo(200);
        submit(base, student, offeringId, text);
    }

    private String submissionState(String offeringId) {
        return jdbc.queryForObject(
                "select grading_state from submissions where offering_id = ? and active = true",
                String.class, java.util.UUID.fromString(offeringId));
    }

    private int pendingSubmissions(String offeringId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from submissions where offering_id = ? and grading_state = 'QUEUED'",
                Integer.class, java.util.UUID.fromString(offeringId));
        return n == null ? 0 : n;
    }

    private String runState(String offeringId) {
        return jdbc.queryForObject("select state from grading_runs where offering_id = ?",
                String.class, java.util.UUID.fromString(offeringId));
    }

    private java.sql.Timestamp runFinishedAt(String offeringId) {
        return jdbc.queryForObject("select finished_at from grading_runs where offering_id = ?",
                java.sql.Timestamp.class, java.util.UUID.fromString(offeringId));
    }

    private int runCount(String offeringId) {
        Integer n = jdbc.queryForObject("select count(*) from grading_runs where offering_id = ?",
                Integer.class, java.util.UUID.fromString(offeringId));
        return n == null ? 0 : n;
    }

    private int reviewCount(String offeringId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from reviews r join submissions s on s.id = r.submission_id "
                        + "where s.offering_id = ?", Integer.class, java.util.UUID.fromString(offeringId));
        return n == null ? 0 : n;
    }

    private String activeSubmissionId(String offeringId) {
        return jdbc.queryForObject(
                "select id from submissions where offering_id = ? and active = true",
                String.class, java.util.UUID.fromString(offeringId));
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
        return String.valueOf(parse(request(base, cookie, "POST", path, body)).get(field));
    }

    private List<?> getList(String base, String cookie, String path) throws Exception {
        return parseList(request(base, cookie, "GET", path, null));
    }



    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(HttpResponse<String> res) throws Exception {
        assertThat(res.statusCode()).withFailMessage(res.body()).isEqualTo(200);
        return res.body() == null || res.body().isBlank() ? Map.of() : JSON.readValue(res.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<?> parseList(HttpResponse<String> res) throws Exception {
        assertThat(res.statusCode()).withFailMessage(res.body()).isEqualTo(200);
        return res.body() == null || res.body().isBlank() ? List.of() : JSON.readValue(res.body(), List.class);
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
            b.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
