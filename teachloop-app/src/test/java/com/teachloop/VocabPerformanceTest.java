package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** T412：1 万词队列 p95、1000/10000 行导入；内存线性增长问题单列记录（见 tasks.md）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false"
        })
@Testcontainers
class VocabPerformanceTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("teachloop.grading.poll-ms", () -> "3600000");
        registry.add("teachloop.grading.dispatch-ms", () -> "3600000");
        registry.add("teachloop.grading.sweep-ms", () -> "3600000");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("1 万词规模：今日队列接口 p95 < 200ms（40 次采样）")
    void queueP95Under200ms() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        seedWords(userId, 10_000, "perf");
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_words where user_id = ?", Integer.class, userId))
                .isEqualTo(10_000);

        List<Long> samples = new ArrayList<>();
        for (int round = 0; round < 40; round++) {
            long started = System.nanoTime();
            HttpResponse<String> response = get(base, cookie, "/api/vocab/review/queue?limit=100");
            assertThat(response.statusCode()).isEqualTo(200);
            samples.add((System.nanoTime() - started) / 1_000_000);
        }
        samples.sort(Long::compareTo);
        long p95 = samples.get((int) Math.ceil(samples.size() * 0.95) - 1);
        System.out.println("[T412] queue p95=" + p95 + "ms samples=" + samples);
        assertThat(p95).as("队列 p95 (ms)").isLessThan(200);
    }

    @Test
    @DisplayName("导入：1000 行成功且 <30s；10000 行成功（记录耗时与堆增长）")
    void importThousandAndTenThousand() throws Exception {
        String base = base();
        String cookie = login(base, "teacher@teachloop.local");
        long first = importLines(base, cookie, 1_000, "thou");
        assertThat(first).isLessThan(30_000);
        long second = importLines(base, cookie, 10_000, "tenk");
        assertThat(second).isGreaterThan(0);
    }

    private long importLines(String base, String cookie, int count, String prefix) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int index = 1; index <= count; index++) {
            text.append(prefix).append(String.format("%05d", index)).append('\n');
        }
        long before = usedHeapMb();
        long started = System.nanoTime();
        HttpResponse<String> response = post(base, cookie, "/api/vocab/import/commit",
                Map.of("text", text.toString(), "language", "en"));
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode counts = MAPPER.readTree(response.body()).get("counts");
        assertThat(counts.get("created").asInt()).isEqualTo(count);
        System.out.println("[T412] import " + count + " rows=" + elapsed + "ms heapDelta="
                + (usedHeapMb() - before) + "MB");
        return elapsed;
    }

    private void seedWords(UUID userId, int count, String prefix) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> words = new ArrayList<>(count);
        List<Object[]> progress = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            UUID id = UUID.randomUUID();
            String form = prefix + String.format("%05d", index);
            words.add(new Object[]{id, userId, "en", form, form, now, now});
            progress.add(new Object[]{id, userId, now, now});
        }
        jdbc.batchUpdate("""
                insert into vocab_words (id, user_id, language, form, normalized_form, content_status,
                                         removed, deleted, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'PENDING', false, false, ?, ?)
                """, words);
        jdbc.batchUpdate("""
                insert into vocab_progress (word_id, user_id, state, due_at, interval_days, ease,
                                            lapses, reviewed_count, updated_at)
                values (?, ?, 'NEW', ?, 0, 2.50, 0, 0, ?)
                """, progress);
    }

    private long usedHeapMb() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private UUID me(String base, String cookie) throws Exception {
        return UUID.fromString(MAPPER.readTree(get(base, cookie, "/api/auth/me").body())
                .get("id").asString());
    }

    private HttpResponse<String> get(String base, String cookie, String path) throws Exception {
        return send(base, cookie, "GET", path, null);
    }

    private HttpResponse<String> post(String base, String cookie, String path, Object body)
            throws Exception {
        return send(base, cookie, "POST", path, body);
    }

    private HttpResponse<String> send(String base, String cookie, String method, String path,
                                      Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN");
        if (cookie != null) builder.header("Cookie", cookie);
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body == null ? "" : MAPPER.writeValueAsString(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String base, String email) throws Exception {
        HttpResponse<String> response = send(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
