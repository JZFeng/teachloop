package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.generation.GenerationJobStore;
import com.teachloop.vocab.generation.VocabularyGenerationWorker;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * T426 验收：导入默认不再全量生成；学生复习/看详情时按窗口把缺内容的词排到队首。
 *
 * <p>默认 {@code generation.on-import=false}；stub 生成器保证不触网。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.engine=stub",
                "teachloop.vocab.generation.poll-ms=3600000",
                "teachloop.vocab.review.prepare-first=20"
        })
@Testcontainers
class VocabPrepareTest {

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

    @Autowired
    VocabularyGenerationWorker worker;

    @Autowired
    GenerationJobStore jobs;

    private final HttpClient client = HttpClient.newHttpClient();

    @org.junit.jupiter.api.BeforeEach
    void cleanVocab() {
        jdbc.update("delete from vocab_generation_jobs");
        jdbc.update("delete from vocab_review_logs");
        jdbc.update("delete from vocab_progress");
        jdbc.update("delete from vocab_word_sources");
        jdbc.update("delete from vocab_words");
    }

    @Test
    @DisplayName("默认惰性：导入不入队；prepare TODAY 才建高优先级任务，生成后转 READY")
    void prepareOnDemand() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base);
        UUID wordId = importWord(base, cookie, "kettle");

        assertThat(jobCount(wordId)).as("导入默认不再全量生成").isZero();
        assertThat(wordStatus(wordId)).isEqualTo("PENDING");

        Map<?, ?> first = prepare(base, cookie, Map.of(
                "scope", "TODAY", "language", "en", "upTo", 20));
        assertThat(first.get("requested")).isEqualTo(1);
        assertThat(first.get("ready")).isEqualTo(0);
        assertThat(first.get("pending")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select priority from vocab_generation_jobs where subject_id = ?",
                Integer.class, wordId)).isEqualTo(GenerationJobStore.PRIORITY_ON_DEMAND);

        worker.poll();
        assertThat(wordStatus(wordId)).isEqualTo("READY");

        Map<?, ?> second = prepare(base, cookie, Map.of(
                "scope", "TODAY", "language", "en", "upTo", 20));
        assertThat(second.get("ready")).isEqualTo(1);
        assertThat(second.get("pending")).isEqualTo(0);
        assertThat(jobCount(wordId)).isEqualTo(1);
    }

    @Test
    @DisplayName("详情页按词准备：prepare WORD 为单个词入队")
    void prepareSingleWord() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base);
        UUID wordId = importWord(base, cookie, "lantern");
        assertThat(jobCount(wordId)).isZero();

        Map<?, ?> result = prepare(base, cookie, Map.of("scope", "WORD", "wordId", wordId.toString()));
        assertThat(result.get("requested")).isEqualTo(1);
        assertThat(jobCount(wordId)).isEqualTo(1);
    }

    @Test
    @DisplayName("排队顺序：按需准备（priority 100）先于后台任务（priority 0）被领取")
    void onDemandJobsClaimFirst() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base);
        UUID backgroundWord = importWord(base, cookie, "meadow");
        UUID urgentWord = importWord(base, cookie, "pencil");

        // 模拟后台批量任务与按需任务并存：两条都 QUEUED，后台的先创建。
        insertJob(backgroundWord, GenerationJobStore.PRIORITY_BACKGROUND, Instant.now().minusSeconds(60));
        insertJob(urgentWord, GenerationJobStore.PRIORITY_ON_DEMAND, Instant.now());

        List<GenerationJobStore.Job> claimed = jobs.claim(1, "test-worker", Instant.now());
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).subjectId()).isEqualTo(urgentWord);
    }

    private void insertJob(UUID wordId, int priority, Instant nextRunAt) {
        jdbc.update("""
                insert into vocab_generation_jobs
                    (subject_type, subject_id, state, attempts, next_run_at, created_at, updated_at,
                     priority)
                values ('WORD', ?, 'QUEUED', 0, ?, now(), now(), ?)
                """, wordId, java.sql.Timestamp.from(nextRunAt), priority);
    }

    private Map<?, ?> prepare(String base, String cookie, Map<String, Object> body) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(base + "/api/vocab/review/prepare"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readValue(response.body(), Map.class);
    }

    private UUID importWord(String base, String cookie, String word) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("text", word, "language", "en"));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(base + "/api/vocab/import/commit"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return jdbc.queryForObject("select id from vocab_words where normalized_form = ?",
                UUID.class, word.toLowerCase());
    }

    private long jobCount(UUID wordId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from vocab_generation_jobs where subject_type = 'WORD' and subject_id = ?",
                Integer.class, wordId);
        return count == null ? 0 : count;
    }

    private String wordStatus(UUID wordId) {
        return jdbc.queryForObject("select content_status from vocab_words where id = ?",
                String.class, wordId);
    }

    private String login(String base) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "email", "student@teachloop.local", "password", "teachloop123"));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(base + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
