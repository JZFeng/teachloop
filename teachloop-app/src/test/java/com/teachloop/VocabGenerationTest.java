package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.content.ContentView;
import com.teachloop.vocab.generation.VocabularyGenerationWorker;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T394 集成验收：同词只入队一次、成功置 READY、3 次失败置 FAILED、24 小时超时标内容缺失。
 *
 * <p>用 stub 生成器（词形含 fail 必失败）；scheduled 轮询设成 1 小时，测试里手动调 poll。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.engine=stub",
                "teachloop.vocab.generation.poll-ms=3600000",
                "teachloop.vocab.generation.daily-budget=2000",
                "teachloop.vocab.generation.on-import=true"
        })
@Testcontainers
class VocabGenerationTest {

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
    ContentStore contentStore;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("成功：同词重复导入只入队一次；poll 后 content_status=READY 且 content 写库")
    void successAndIdempotent() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base);
        UUID wordId = importWord(base, cookie, "apple");
        importWord(base, cookie, "apple");
        assertThat(jobCount(wordId)).isEqualTo(1);
        assertThat(wordStatus(wordId)).isEqualTo("PENDING");
        assertThat(contentId(wordId)).isNull();

        worker.poll();

        assertThat(jobCount(wordId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from vocab_generation_jobs where subject_id = ?",
                String.class, wordId)).isEqualTo("SUCCEEDED");
        assertThat(wordStatus(wordId)).isEqualTo("READY");
        Long contentId = contentId(wordId);
        assertThat(contentId).as("T429：AI 成功内容写入全局 delta，用户行只存引用").isNotNull();
        String stored = contentStore.getById(contentId).orElseThrow().json();

        // (a) 落库形状是 v2（T429 起的约定）：schemaVersion=2 + meanings，
        //     不再是 v1 的 senses；断言落库就别再找 v1 字段。
        JsonNode storedJson = MAPPER.readTree(stored);
        assertThat(storedJson.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(storedJson.path("word").asString()).isEqualTo("apple");
        assertThat(storedJson.path("meanings").path("zh").asString()).isNotBlank();
        assertThat(stored).doesNotContain("\"senses\"");

        // (b) 读路径要能把 v2 投影成前端仍在用的 v1 形状：senses / examples[{en,zh}] 必须出现。
        JsonNode view = ContentView.toView(storedJson);
        assertThat(view.path("senses").size()).isGreaterThan(0);
        assertThat(view.path("senses").get(0).path("zh").asString()).isNotBlank();
        assertThat(view.path("examples").size()).isGreaterThan(0);
        assertThat(view.path("examples").get(0).path("en").asString()).isNotBlank();
        assertThat(view.path("examples").get(0).path("zh").asString()).isNotBlank();
    }

    @Test
    @DisplayName("失败重试：第 1、2 次回 QUEUED 退避，第 3 次 FAILED 且词条标生成失败")
    void retriesThreeTimesThenFails() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base);
        UUID wordId = importWord(base, cookie, "failword");

        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt > 1) {
                jdbc.update("update vocab_generation_jobs set next_run_at = now() - interval '1 minute' where subject_id = ?",
                        wordId);
            }
            worker.poll();
        }

        assertThat(jdbc.queryForObject(
                "select attempts from vocab_generation_jobs where subject_id = ?", Integer.class, wordId))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select state from vocab_generation_jobs where subject_id = ?", String.class, wordId))
                .isEqualTo("FAILED");
        assertThat(wordStatus(wordId)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("24 小时超时：job 移出队列、词条标内容缺失")
    void staleAfterTwentyFourHours() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base);
        UUID wordId = importWord(base, cookie, "stale");
        jdbc.update("update vocab_generation_jobs set created_at = now() - interval '25 hours'"
                + " where subject_id = ?", wordId);

        worker.poll();

        assertThat(jdbc.queryForObject(
                "select state from vocab_generation_jobs where subject_id = ?", String.class, wordId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "select last_error from vocab_generation_jobs where subject_id = ?", String.class, wordId))
                .contains("content missing");
        assertThat(wordStatus(wordId)).isEqualTo("FAILED");
        // 已不在 QUEUED，不会再被领取
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_generation_jobs where subject_id = ? and state = 'QUEUED'",
                Integer.class, wordId)).isZero();
    }


    @Test
    @DisplayName("T395 重试：3 次失败后手动重试 → job QUEUED、attempts 归零、词条回 PENDING，再 poll 会重新领取")
    void retryAfterFinalFailure() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base);
        UUID wordId = importWord(base, cookie, "failagain");
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt > 1) {
                jdbc.update("update vocab_generation_jobs set next_run_at = now() - interval '1 minute' where subject_id = ?", wordId);
            }
            worker.poll();
        }
        assertThat(jdbc.queryForObject(
                "select state from vocab_generation_jobs where subject_id = ?", String.class, wordId))
                .isEqualTo("FAILED");

        String retryBody = MAPPER.writeValueAsString(Map.of("ids", List.of(wordId)));
        HttpResponse<String> retry = client.send(HttpRequest.newBuilder(
                                URI.create(base + "/api/vocab/words/retry"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString(retryBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "select state from vocab_generation_jobs where subject_id = ?", String.class, wordId))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "select attempts from vocab_generation_jobs where subject_id = ?", Integer.class, wordId))
                .isZero();
        assertThat(wordStatus(wordId)).isEqualTo("PENDING");
        worker.poll();
        assertThat(jdbc.queryForObject(
                "select attempts from vocab_generation_jobs where subject_id = ?", Integer.class, wordId))
                .isEqualTo(1);
    }

    private UUID importWord(String base, String cookie, String word) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("text", word, "language", "en"));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(base + "/api/vocab/import/commit"))
                        .header("Content-Type", "application/json")
                        .header("Accept-Language", "zh-CN")
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

    private Long contentId(UUID wordId) {
        return jdbc.queryForObject("select content_id from vocab_words where id = ?",
                Long.class, wordId);
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
