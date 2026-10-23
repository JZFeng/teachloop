package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teachloop.grading.service.GradingWorker;
import com.teachloop.sitting.domain.OfferingRepository;
import com.teachloop.sitting.service.TeacherGradingPreferenceService;
import com.teachloop.sitting.service.TeacherGradingScheduler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 决策 010：作业按教师级偏好派发。
 *
 * <p>覆盖四条：
 * <ol>
 *   <li>固定时间模式不看 due：截止在未来的作业也会在教师设置的时刻被派发；同一本地日期不重复派发；</li>
 *   <li>due-date 模式过了 due+grace 就派发，且在高峰时刻也派发（不经过峰谷限制）；</li>
 *   <li>考试仍不在作业调度范围；</li>
 *   <li>学生重交后，复核队列只保留最新一版。</li>
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
        // 测试里手动调 scheduler.scan(now)，不让后台扫描抢先。
        r.add("teachloop.grading.preference-scan-ms", () -> "3600000");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TeacherGradingScheduler scheduler;

    @Autowired
    TeacherGradingPreferenceService preferences;

    @Autowired
    OfferingRepository offerings;

    @Autowired
    GradingWorker worker;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("固定时间不看 due、同日不重复；due-date 高峰也派发；考试不在作业调度；队列只留最新 attempt")
    void teacherPreferenceDrivesAssignmentDispatch() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base, "teacher@teachloop.local");
        String student = login(base, "student@teachloop.local");
        UUID teacherId = userId("teacher@teachloop.local");

        // 端点：教师能读写自己的偏好；学生 403；默认固定时间 02:30 Asia/Shanghai。
        Map<String, Object> defaults = parse(request(base, teacher, "GET",
                "/api/profile/grading-preferences", null));
        assertThat(defaults.get("triggerMode")).isEqualTo("FIXED_TIME");
        assertThat(defaults.get("fixedTime")).isEqualTo("02:30");
        assertThat(defaults.get("timeZone")).isEqualTo("Asia/Shanghai");
        Map<String, Object> saved = parse(request(base, teacher, "PUT",
                "/api/profile/grading-preferences",
                Map.of("triggerMode", "DUE_DATE", "fixedTime", "03:15",
                        "timeZone", "UTC", "catchUp", false)));
        assertThat(saved.get("triggerMode")).isEqualTo("DUE_DATE");
        assertThat(saved.get("fixedTime")).isEqualTo("03:15");
        assertThat(saved.get("catchUp")).isEqualTo(false);
        assertThat(request(base, student, "GET", "/api/profile/grading-preferences", null).statusCode())
                .isEqualTo(403);

        String classId = post(base, teacher, "/api/classes", Map.of("name", "教师级批改班"), "id");
        String code = String.valueOf(getList(base, teacher, "/api/classes").stream()
                .map(o -> (Map<?, ?>) o)
                .filter(c -> classId.equals(c.get("id")))
                .findFirst().orElseThrow().get("inviteCode"));
        post(base, student, "/api/classes/join", Map.of("inviteCode", code), "name");

        // ① 固定时间：截止在未来也必须派发；同一本地日期第二次扫描不再派发。
        Instant fixedNow = Instant.parse("2026-10-23T02:00:00Z"); // 10:00 Asia/Shanghai，高峰
        String fixedOffering = post(base, teacher, "/api/offerings", Map.of(
                "title", "固定时间作业",
                "instructions", "写一段话介绍你的城市。",
                "totalPoints", 10,
                "rubricMarkdown", "# Scoring Rubric\n\n### 1. Content — 5 points\n- Yes: 5 points\n\n"
                        + "### 2. Language — 5 points\n- Yes: 5 points\n",
                "dueAt", fixedNow.plus(7, ChronoUnit.DAYS).toString(),
                "graceHours", 0,
                "maxAttempts", 3,
                "timingMode", "INDIVIDUAL",
                "classes", List.of(Map.of("classId", classId)),
                "publish", true), "id");
        submit(base, student, fixedOffering, "My city is small but lively.");
        assertThat(submissionState(fixedOffering)).isEqualTo("QUEUED");

        preferences.update(teacherId, teacherId,
                new TeacherGradingPreferenceService.UpdateRequest("FIXED_TIME", "09:59", "Asia/Shanghai", true));
        scheduler.scan(fixedNow);
        assertThat(submissionState(fixedOffering)).isEqualTo("PENDING");
        assertThat(runCount(fixedOffering)).isEqualTo(1);

        worker.processBatch(10);
        assertThat(submissionState(fixedOffering)).isEqualTo("DONE");
        assertThat(runState(fixedOffering)).isEqualTo("done");

        scheduler.scan(fixedNow.plusSeconds(30));
        assertThat(submissionState(fixedOffering)).isEqualTo("DONE");
        assertThat(runCount(fixedOffering)).isEqualTo(1);

        // ② due-date：过了 due+grace 就派发；fixedNow 是高峰（10:00 Asia/Shanghai），旧策略会拒绝。
        String dueOffering = post(base, teacher, "/api/offerings", Map.of(
                "title", "截止时间作业",
                "instructions", "写一段话介绍你的学校。",
                "totalPoints", 10,
                "rubricMarkdown", "# Scoring Rubric\n\n### 1. Content — 5 points\n- Yes: 5 points\n\n"
                        + "### 2. Language — 5 points\n- Yes: 5 points\n",
                "dueAt", fixedNow.minus(4, ChronoUnit.HOURS).toString(),
                "graceHours", 0,
                "maxAttempts", 3,
                "timingMode", "INDIVIDUAL",
                "classes", List.of(Map.of("classId", classId)),
                "publish", true), "id");
        submit(base, student, dueOffering, "My school is big and busy.");
        assertThat(submissionState(dueOffering)).isEqualTo("QUEUED");
        assertThat(offerings.findDueAssignmentsForGrading(teacherId, fixedNow))
                .contains(UUID.fromString(dueOffering));

        preferences.update(teacherId, teacherId,
                new TeacherGradingPreferenceService.UpdateRequest("DUE_DATE", "02:30", "Asia/Shanghai", true));
        scheduler.scan(fixedNow);
        assertThat(submissionState(dueOffering)).isEqualTo("PENDING");
        assertThat(runCount(dueOffering)).isEqualTo(1);

        // ③ 考试不在作业到期查询里（现有行为保持）。
        assertThat(offerings.findDueForGrading(fixedNow)).doesNotContain(UUID.fromString(dueOffering));

        worker.processBatch(10);
        assertThat(submissionState(dueOffering)).isEqualTo("DONE");

        // ④ 学生重交：旧 attempt 变 inactive，新提交补进同一个 run；队列只留最新一版。
        resubmit(base, student, fixedOffering, "My city is small but lively, and the food is great.");
        assertThat(pendingSubmissions(fixedOffering)).isEqualTo(1);
        preferences.update(teacherId, teacherId,
                new TeacherGradingPreferenceService.UpdateRequest("FIXED_TIME", "09:59", "Asia/Shanghai", true));
        scheduler.scan(fixedNow.plus(1, ChronoUnit.DAYS));
        assertThat(runState(fixedOffering)).isEqualTo("running");
        worker.processBatch(10);
        assertThat(runState(fixedOffering)).isEqualTo("done");

        List<String> queueSubmissionIds = getList(base, teacher, "/api/grading/queue").stream()
                .map(o -> String.valueOf(((Map<?, ?>) o).get("submissionId")))
                .toList();
        List<String> fixedSubmissionIds = jdbc.queryForList(
                "select id from submissions where offering_id = ?", String.class, UUID.fromString(fixedOffering));
        assertThat(queueSubmissionIds.stream().filter(fixedSubmissionIds::contains).toList())
                .as("重交后队列只应出现最新一次提交")
                .containsExactly(activeSubmissionId(fixedOffering));
    }

    // ---------------------------------------------------------------- helpers

    private UUID userId(String email) {
        return UUID.fromString(jdbc.queryForObject("select id from users where email = ?", String.class, email));
    }

    private void submit(String base, String student, String offeringId, String text) throws Exception {
        HttpResponse<String> started = request(base, student, "POST",
                "/api/offerings/" + offeringId + "/start", Map.of());
        assertThat(started.statusCode()).withFailMessage("start: " + started.body()).isEqualTo(200);
        HttpResponse<String> submitted = request(base, student, "POST",
                "/api/offerings/" + offeringId + "/submissions", Map.of("contentText", text));
        assertThat(submitted.statusCode()).withFailMessage("submit: " + submitted.body()).isEqualTo(200);
    }

    private void resubmit(String base, String student, String offeringId, String text) throws Exception {
        HttpResponse<String> attempt = request(base, student, "POST",
                "/api/offerings/" + offeringId + "/attempts", Map.of());
        assertThat(attempt.statusCode()).withFailMessage("new attempt: " + attempt.body()).isEqualTo(200);
        submit(base, student, offeringId, text);
    }

    private String submissionState(String offeringId) {
        return jdbc.queryForObject(
                "select grading_state from submissions where offering_id = ? and active = true",
                String.class, UUID.fromString(offeringId));
    }

    private int pendingSubmissions(String offeringId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from submissions where offering_id = ? and grading_state = 'QUEUED'",
                Integer.class, UUID.fromString(offeringId));
        return n == null ? 0 : n;
    }

    private String runState(String offeringId) {
        return jdbc.queryForObject("select state from grading_runs where offering_id = ?",
                String.class, UUID.fromString(offeringId));
    }

    private int runCount(String offeringId) {
        Integer n = jdbc.queryForObject("select count(*) from grading_runs where offering_id = ?",
                Integer.class, UUID.fromString(offeringId));
        return n == null ? 0 : n;
    }

    private String activeSubmissionId(String offeringId) {
        return jdbc.queryForObject(
                "select id from submissions where offering_id = ? and active = true",
                String.class, UUID.fromString(offeringId));
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
