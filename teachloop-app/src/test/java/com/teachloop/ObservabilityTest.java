package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.observability.domain.AuditLog;
import com.teachloop.observability.domain.AuditLogRepository;
import com.teachloop.observability.domain.DailyStat;
import com.teachloop.observability.domain.DailyStatRepository;
import com.teachloop.observability.service.DailyStatsService;
import com.teachloop.observability.service.LogRetentionService;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T140–T144 验收：请求日志、前端事件白名单、90 天清理、daily_stats 聚合。
 *
 * <p>日志目录用占位临时目录（{@code teachloop.observability.log-dir}），与开发日志隔离；
 * 请求日志断言「字段齐全且不含正文」，事件断言「非白名单 400 且不落盘、不入业务库」，
 * 清理断言「过期文件被删、审计行数不变」，汇总断言「重跑幂等、数字与文件一致」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ObservabilityTest {

    private static final Path LOG_DIR = createTempDir();

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.observability.log-dir", LOG_DIR::toString);
        // 测试里手动调用清理与聚合；把定时触发推到不会命中的时间
        r.add("teachloop.observability.cleanup-ms", () -> "3600000");
        r.add("teachloop.observability.daily-stats-cron", () -> "0 0 0 1 1 *");
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired
    Environment env;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    DailyStatRepository stats;

    @Autowired
    DailyStatsService dailyStats;

    @Autowired
    LogRetentionService retention;

    private final HttpClient client = HttpClient.newHttpClient();

    private String base;
    private String teacher;
    private String teacherId;

    @BeforeEach
    void setUp() throws Exception {
        base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        HttpResponse<String> login = raw(null, "POST", "/api/auth/login",
                Map.of("email", "teacher@teachloop.local", "password", "teachloop123"), Map.of());
        assertThat(login.statusCode()).as("login: %s", login.body()).isEqualTo(200);
        teacherId = Json.parse(login.body()).get("id").toString();
        teacher = cookieOf(login);
    }

    // ================= T140 请求日志 =================

    @Test
    @DisplayName("T140：请求日志字段齐全，路由为用户 id 与方法，状态/耗时正确，且不含正文")
    void requestLogHasFieldsAndNoBody() throws Exception {
        String requestId = "obs-" + UUID.randomUUID();
        HttpResponse<String> queue = raw(teacher, "GET", "/api/grading/queue", null,
                Map.of(REQUEST_ID_HEADER, requestId));
        assertThat(queue.statusCode()).isEqualTo(200);

        // 带唯一正文令牌的失败请求：响应体会回显事件名，但请求日志绝不能出现它
        String secret = "SECRET_BODY_" + UUID.randomUUID();
        HttpResponse<String> invalid = raw(teacher, "POST", "/api/events",
                Map.of("events", List.of(Map.of("name", secret))), Map.of());
        assertThat(invalid.statusCode()).isEqualTo(400);

        List<Map<String, Object>> lines = readJsonLines(requestsFile());
        Map<String, Object> logged = lines.stream()
                .filter(line -> requestId.equals(line.get("requestId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("请求日志缺少 requestId=" + requestId));

        assertThat(logged.get("method")).isEqualTo("GET");
        assertThat(logged.get("route")).isEqualTo("/api/grading/queue");
        assertThat(logged.get("status")).isEqualTo(200);
        assertThat(((Number) logged.get("durationMs")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(logged.get("userId")).isEqualTo(teacherId);
        assertThat(Instant.parse(logged.get("ts").toString())).isNotNull();

        String wholeFile = Files.readString(requestsFile(), StandardCharsets.UTF_8);
        assertThat(wholeFile)
                .as("请求/响应正文与密码绝不能进日志")
                .doesNotContain(secret)
                .doesNotContain("teachloop123")
                .doesNotContain("password")
                .doesNotContain("contentText");
    }

    // ================= T141 前端事件 =================

    @Test
    @DisplayName("T141：白名单事件批量写 JSONL；非白名单 400 且不落盘、不写业务库")
    void eventsWhitelistAndJsonlOnly() throws Exception {
        long auditsBefore = auditLogs.count();
        long statsBefore = stats.count();
        String requestId = "evt-" + UUID.randomUUID();

        Map<String, Object> accepted = send(teacher, "POST", "/api/events",
                Map.of("events", List.of(
                        Map.of("name", "page_view", "props", Map.of("route", "/student")),
                        Map.of("name", "action_click", "props", Map.of("action", "open_feedback")),
                        Map.of("name", "exam_start"),
                        Map.of("name", "answer_save"),
                        Map.of("name", "submit"))),
                Map.of(REQUEST_ID_HEADER, requestId));
        assertThat(accepted.get("accepted")).isEqualTo(5);

        List<Map<String, Object>> lines = readJsonLines(eventsFile()).stream()
                .filter(line -> requestId.equals(line.get("requestId")))
                .toList();
        assertThat(lines).hasSize(5);
        assertThat(lines).extracting(line -> line.get("name"))
                .containsExactly("page_view", "action_click", "exam_start", "answer_save", "submit");
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.get("userId")).isEqualTo(teacherId);
            assertThat(Instant.parse(line.get("ts").toString())).isNotNull();
        });

        // 事件明细只在文件里，不入业务库
        assertThat(auditLogs.count()).isEqualTo(auditsBefore);
        assertThat(stats.count()).isEqualTo(statsBefore);

        // 非白名单：400，且不追加任何行
        long linesBefore = Files.readAllLines(eventsFile(), StandardCharsets.UTF_8).size();
        HttpResponse<String> rejected = raw(teacher, "POST", "/api/events",
                Map.of("events", List.of(Map.of("name", "drop_table"))), Map.of());
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(Files.readAllLines(eventsFile(), StandardCharsets.UTF_8)).hasSize((int) linesBefore);
    }

    // ================= T142 清理（审计不删） =================

    @Test
    @DisplayName("T142：删除超过保留期的日志文件，保留近期文件；审计行只增不减")
    void cleanupDeletesExpiredFilesButNotAudit() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Path expiredRequests = LOG_DIR.resolve("requests-" + today.minusDays(91) + ".jsonl");
        Path expiredEvents = LOG_DIR.resolve("events-" + today.minusDays(91) + ".jsonl");
        Path recent = LOG_DIR.resolve("requests-" + today.minusDays(10) + ".jsonl");
        Path unrelated = LOG_DIR.resolve("notes.txt");
        Files.createDirectories(LOG_DIR);
        Files.writeString(expiredRequests, "{}\n", StandardCharsets.UTF_8);
        Files.writeString(expiredEvents, "{}\n", StandardCharsets.UTF_8);
        Files.writeString(recent, "{}\n", StandardCharsets.UTF_8);
        Files.writeString(unrelated, "keep me\n", StandardCharsets.UTF_8);

        // 一条很老的审计记录：清理绝不碰审计表
        auditLogs.save(new AuditLog(UUID.randomUUID(), UUID.fromString(teacherId),
                "legacy:test", "submission", UUID.randomUUID().toString(), null,
                Instant.now().minus(300, ChronoUnit.DAYS)));
        long auditsBefore = auditLogs.count();

        int deleted = retention.deleteBefore(today);

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(expiredRequests).doesNotExist();
        assertThat(expiredEvents).doesNotExist();
        assertThat(recent).exists();
        assertThat(unrelated).exists();
        assertThat(auditLogs.count()).as("审计永久保留，清理不删 audit_logs").isEqualTo(auditsBefore);
    }

    // ================= T144 每日汇总 =================

    @Test
    @DisplayName("T144：daily_stats 数字与当天文件一致，重跑幂等且不产生重复行")
    void dailyStatsAggregateIsIdempotent() throws Exception {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        Files.createDirectories(LOG_DIR);
        Files.writeString(LOG_DIR.resolve("requests-" + day + ".jsonl"),
                String.join("\n", List.of(
                        requestLine("r1", "GET", "/api/papers", 200, 100),
                        requestLine("r2", "GET", "/api/offerings", 200, 200),
                        requestLine("r3", "GET", "/api/missing", 404, 300),
                        requestLine("r4", "POST", "/api/offerings/{offeringId}/grading/dispatch", 200, 4000),
                        requestLine("r5", "GET", "/api/error", 500, 500))) + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(LOG_DIR.resolve("events-" + day + ".jsonl"),
                String.join("\n", List.of(
                        eventLine("page_view"), eventLine("page_view"), eventLine("action_click"),
                        eventLine("submit"), eventLine("exam_start"))) + "\n",
                StandardCharsets.UTF_8);

        Map<String, BigDecimal> first = dailyStats.aggregate(day);
        Map<String, BigDecimal> second = dailyStats.aggregate(day);

        assertThat(first).containsAllEntriesOf(Map.of(
                DailyStatsService.PAGE_VIEWS, BigDecimal.valueOf(2),
                DailyStatsService.ACTIONS, BigDecimal.valueOf(1),
                DailyStatsService.SUBMISSIONS, BigDecimal.valueOf(1),
                DailyStatsService.GRADINGS, BigDecimal.valueOf(1),
                DailyStatsService.TOTAL_REQUESTS, BigDecimal.valueOf(5),
                DailyStatsService.ERRORS, BigDecimal.valueOf(1),
                DailyStatsService.SLOW_REQUESTS, BigDecimal.valueOf(1),
                DailyStatsService.P95_LATENCY_MS, BigDecimal.valueOf(4000)));
        assertThat(second).isEqualTo(first);

        List<DailyStat> rows = stats.findByDay(day);
        assertThat(rows).hasSize(8);
        assertThat(rows).extracting(DailyStat::getMetric)
                .containsExactlyInAnyOrderElementsOf(first.keySet());
        Map<String, BigDecimal> stored = new LinkedHashMap<>();
        rows.forEach(row -> stored.put(row.getMetric(), row.getValue()));
        stored.forEach((metric, value) ->
                assertThat(value).as("metric %s", metric).isEqualByComparingTo(first.get(metric)));
    }

    // ================= helpers =================

    private static Path createTempDir() {
        try {
            Path dir = Files.createTempDirectory("teachloop-observability-test-");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建临时日志目录", ex);
        }
    }

    private Path requestsFile() {
        return LOG_DIR.resolve("requests-" + LocalDate.now(ZoneOffset.UTC) + ".jsonl");
    }

    private Path eventsFile() {
        return LOG_DIR.resolve("events-" + LocalDate.now(ZoneOffset.UTC) + ".jsonl");
    }

    private static String requestLine(String requestId, String method, String route, int status,
                                      long durationMs) {
        return Json.write(Map.of(
                "ts", Instant.parse("2026-10-19T00:00:00Z").toString(),
                "requestId", requestId,
                "userId", "00000000-0000-0000-0000-000000000001",
                "method", method,
                "route", route,
                "status", status,
                "durationMs", durationMs));
    }

    private static String eventLine(String name) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.parse("2026-10-19T00:00:00Z").toString());
        line.put("requestId", "e-" + name);
        line.put("userId", "00000000-0000-0000-0000-000000000001");
        line.put("name", name);
        line.put("props", Map.of());
        return Json.write(line);
    }

    private static List<Map<String, Object>> readJsonLines(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                rows.add(Json.parse(line));
            }
        }
        return rows;
    }

    private static String cookieOf(HttpResponse<String> res) {
        List<String> cookies = res.headers().allValues("set-cookie");
        return cookies.stream()
                .map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("登录没有下发会话 cookie：" + cookies));
    }

    private Map<String, Object> send(String cookie, String method, String path, Object body)
            throws Exception {
        return send(cookie, method, path, body, Map.of());
    }

    private Map<String, Object> send(String cookie, String method, String path, Object body,
                                     Map<String, String> headers) throws Exception {
        HttpResponse<String> res = raw(cookie, method, path, body, headers);
        assertThat(res.statusCode()).withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private HttpResponse<String> raw(String cookie, String method, String path, Object body,
                                     Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        headers.forEach(b::header);
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
        private static final tools.jackson.databind.ObjectMapper MAPPER =
                new tools.jackson.databind.ObjectMapper();

        static String write(Object o) {
            return MAPPER.writeValueAsString(o);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) {
            return MAPPER.readValue(s, Map.class);
        }
    }
}
