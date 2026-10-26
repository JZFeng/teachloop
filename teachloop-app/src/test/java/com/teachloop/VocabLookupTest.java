package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.generation.GenerationJobStore;
import com.teachloop.vocab.generation.VocabularyGenerationWorker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * T515 / AC-60.13 验收：查词即生成（2026-10-26 用户 Bug）。
 *
 * <p>不在库的词进查词页就直接入队一次 AI 生成：不建 {@code vocab_words} 行、不进复习队列，
 * 生成结果只写全局内容；同一词形第二次查词直接命中、不重复入队；失败要用户点「重试」才重置。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.engine=stub",
                "teachloop.vocab.generation.poll-ms=3600000",
                "teachloop.vocab.lookup.generate-on-miss=true"
        })
@Testcontainers
class VocabLookupTest {

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

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void cleanVocab() {
        jdbc.update("delete from vocab_generation_jobs");
        jdbc.update("delete from vocab_review_logs");
        jdbc.update("delete from vocab_progress");
        jdbc.update("delete from vocab_word_sources");
        jdbc.update("delete from vocab_words");
    }

    @Test
    @DisplayName("不在库的词查一次就入队 FORM 任务，生成后查词直接 READY、不建词条")
    void lookupGeneratesContentWithoutLibraryWord() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        String form = "lookupgadget";

        JsonNode first = lookup(base, cookie, form, false);
        assertThat(first.get("state").asString()).isEqualTo("PENDING");
        assertThat(first.get("detail").get("inLibrary").asBoolean()).isFalse();
        assertThat(first.get("detail").get("content").isNull()).isTrue();

        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_generation_jobs
                 where subject_type = 'FORM' and subject_form = ? and priority = ?
                """, Integer.class, form, GenerationJobStore.PRIORITY_ON_DEMAND)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_words where normalized_form = ?", Integer.class, form))
                .as("查词不建词条，词库不被查词污染").isZero();

        worker.poll();

        JsonNode ready = lookup(base, cookie, form, false);
        assertThat(ready.get("state").asString()).isEqualTo("READY");
        assertThat(ready.get("detail").get("inLibrary").asBoolean()).isFalse();
        assertThat(ready.get("detail").get("content").path("senses").get(0).path("zh").asString())
                .isEqualTo("示例释义（stub）");
        assertThat(jdbc.queryForObject("""
                select state from vocab_generation_jobs
                 where subject_type = 'FORM' and subject_form = ?
                """, String.class, form)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_words where normalized_form = ?", Integer.class, form))
                .isZero();

        // 卡背用的是同一个 by-form 接口：查词后直接 GET 也能看到内容
        JsonNode detail = get(base, cookie, "/api/vocab/words/by-form/" + form);
        assertThat(detail.get("content").path("examples").size()).isPositive();
    }

    @Test
    @DisplayName("同一词形第二个学生查词：命中全局内容，0 个新任务")
    void secondStudentHitsGlobalContent() throws Exception {
        String base = base();
        String first = login(base, "student@teachloop.local");
        String form = "lookupshared";
        lookup(base, first, form, false);
        worker.poll();
        assertThat(lookup(base, first, form, false).get("state").asString()).isEqualTo("READY");

        String second = login(base, "teacher@teachloop.local");
        JsonNode result = lookup(base, second, form, false);
        assertThat(result.get("state").asString()).isEqualTo("READY");
        assertThat(result.get("detail").get("inLibrary").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_generation_jobs where subject_type = 'FORM'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("生成失败：轮询只报 FAILED、不自动重试；显式 retry=true 才清零次数回队列")
    void failedLookupNeedsExplicitRetry() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        String form = "lookupfailword";

        assertThat(lookup(base, cookie, form, false).get("state").asString()).isEqualTo("PENDING");
        worker.poll();

        // 第一次失败只退避（60 秒后重试），不是最终失败
        assertThat(jdbc.queryForObject("""
                select state from vocab_generation_jobs
                 where subject_type = 'FORM' and subject_form = ?
                """, String.class, form)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("""
                select attempts from vocab_generation_jobs
                 where subject_type = 'FORM' and subject_form = ?
                """, Integer.class, form)).isEqualTo(1);

        // 模拟重试用尽：任务 FAILED
        jdbc.update("""
                update vocab_generation_jobs set state = 'FAILED'
                 where subject_type = 'FORM' and subject_form = ?
                """, form);
        assertThat(lookup(base, cookie, form, false).get("state").asString()).isEqualTo("FAILED");

        JsonNode retried = lookup(base, cookie, form, true);
        assertThat(retried.get("state").asString()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                select state from vocab_generation_jobs
                 where subject_type = 'FORM' and subject_form = ?
                """, String.class, form)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("""
                select attempts from vocab_generation_jobs
                 where subject_type = 'FORM' and subject_form = ?
                """, Integer.class, form)).isZero();
    }

    @Test
    @DisplayName("在库的词走自己的 prepare 流程，查词接口不建 FORM 任务")
    void inLibraryWordDoesNotCreateLookupJob() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        String form = "lookupinlibrary";

        JsonNode added = MAPPER.readTree(post(base, cookie, "/api/vocab/words",
                Map.of("form", form, "language", "en")).body());
        UUID wordId = UUID.fromString(added.get("id").asString());

        JsonNode result = lookup(base, cookie, form, false);
        assertThat(result.get("state").asString()).isEqualTo("PENDING");
        assertThat(result.get("detail").get("inLibrary").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_generation_jobs where subject_type = 'FORM'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_generation_jobs where subject_id = ?",
                Integer.class, wordId)).isZero();
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private JsonNode lookup(String base, String cookie, String form, boolean retry)
            throws Exception {
        HttpResponse<String> response = post(base, cookie, "/api/vocab/words/lookup",
                Map.of("form", form, "language", "en", "retry", retry));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private JsonNode get(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, "GET", path, null);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> post(String base, String cookie, String path, Object body)
            throws Exception {
        return send(base, cookie, "POST", path, body);
    }

    private HttpResponse<String> send(String base, String cookie, String method, String path,
                                      Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN");
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
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
