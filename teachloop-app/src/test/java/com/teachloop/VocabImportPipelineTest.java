package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T393 集成验收：预览计数、入库去重、来源批次/标记、重复导入、软删除恢复、粘贴与导入同管线。
 *
 * <p>词表索引关闭（避免每个 app 测试类都装载 4.3MB 词表），family_id/band 允许为空；
 * 索引命中时写 family_id 由 T399 的词族展示覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"teachloop.vocab.enabled=true", "teachloop.vocab.lexicon.enabled=false"})
@Testcontainers
class VocabImportPipelineTest {

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
    @DisplayName("预览 → 提交：新建/重复/失败计数一致；重复导入不新增词条；来源按批次记；软删除恢复原进度")
    void importPipeline() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base, "student@teachloop.local", "teachloop123");
        java.util.UUID userId = me(base, cookie);
        String longWord = "a".repeat(65);
        Map<String, Object> body = Map.of(
                "text", "Apple\napple\nＣＡＦＥ\n" + longWord,
                "language", "en",
                "label", "Unit 5 生词");

        JsonNode preview = post(base, cookie, "/api/vocab/import/preview", body);
        assertThat(preview.get("counts").get("total").asInt()).isEqualTo(4);
        assertThat(preview.get("counts").get("created").asInt()).isEqualTo(2);
        assertThat(preview.get("counts").get("duplicated").asInt()).isEqualTo(1);
        assertThat(preview.get("counts").get("failed").asInt()).isEqualTo(1);
        assertThat(preview.get("lineErrors").get(0).get("line").asInt()).isEqualTo(4);

        JsonNode commit = post(base, cookie, "/api/vocab/import/commit", body);
        assertThat(commit.get("counts").get("created").asInt()).isEqualTo(2);
        assertThat(commit.get("counts").get("duplicated").asInt()).isEqualTo(1);
        assertThat(commit.get("counts").get("failed").asInt()).isEqualTo(1);
        String batchId = commit.get("batchId").asString();
        assertThat(batchId).isNotBlank();

        assertThat(wordCount(userId)).isEqualTo(2);
        assertThat(sourceCount(userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from vocab_progress", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "select label, batch_id::text from vocab_word_sources where word_id in"
                        + " (select id from vocab_words where user_id = ?)", userId))
                .allSatisfy(row -> {
                    assertThat(row.get("label")).isEqualTo("Unit 5 生词");
                    assertThat(row.get("batch_id")).isEqualTo(batchId);
                });
        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_words where user_id = ? and normalized_form = 'cafe'
                """, Integer.class, userId)).isEqualTo(1);

        // 重复导入：不新增词条，只加来源（新批次）
        JsonNode second = post(base, cookie, "/api/vocab/import/commit", body);
        assertThat(second.get("counts").get("created").asInt()).isZero();
        assertThat(second.get("counts").get("duplicated").asInt()).isEqualTo(3);
        assertThat(second.get("counts").get("failed").asInt()).isEqualTo(1);
        assertThat(wordCount(userId)).isEqualTo(2);
        assertThat(sourceCount(userId)).isEqualTo(4);

        // 软删除后再次导入：恢复原词条与原进度（不新建、不重置）
        var appleId = jdbc.queryForObject(
                "select id from vocab_words where user_id = ? and normalized_form = 'apple'",
                java.util.UUID.class, userId);
        jdbc.update("update vocab_words set deleted = true, deleted_at = now() where id = ?", appleId);
        jdbc.update("update vocab_progress set state = 'REVIEW', interval_days = 10 where word_id = ?", appleId);
        JsonNode restored = post(base, cookie, "/api/vocab/import/commit",
                Map.of("text", "apple", "language", "en"));
        assertThat(restored.get("counts").get("created").asInt()).isZero();
        assertThat(restored.get("counts").get("duplicated").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select deleted from vocab_words where id = ?", Boolean.class, appleId))
                .isFalse();
        assertThat(jdbc.queryForObject("select state from vocab_progress where word_id = ?",
                String.class, appleId)).isEqualTo("REVIEW");
        assertThat(wordCount(userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("变音符去重：Résumé / resume / ＲＥＳＵＭＥ 是同一个词；粘贴接口与导入同管线")
    void diacriticsDedupeAndPasteAlias() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base, "teacher@teachloop.local", "teachloop123");
        java.util.UUID userId = me(base, cookie);

        JsonNode result = post(base, cookie, "/api/vocab/import/commit", Map.of(
                "text", "Résumé\nresume\nＲＥＳＵＭＥ", "language", "en", "label", "重音测试"));
        assertThat(result.get("counts").get("created").asInt()).isEqualTo(1);
        assertThat(result.get("counts").get("duplicated").asInt()).isEqualTo(2);
        assertThat(wordCount(userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select form from vocab_words where user_id = ? and normalized_form = 'resume'",
                String.class, userId)).isEqualTo("Résumé");

        JsonNode pasted = post(base, cookie, "/api/vocab/words/paste", Map.of(
                "text", "jolly", "language", "en"));
        assertThat(pasted.get("counts").get("created").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_words where user_id = ? and normalized_form = 'jolly'
                """, Integer.class, userId)).isEqualTo(1);
    }

    private int wordCount(java.util.UUID userId) {
        return jdbc.queryForObject(
                "select count(*) from vocab_words where user_id = ? and language = 'en'",
                Integer.class, userId);
    }

    private int sourceCount(java.util.UUID userId) {
        return jdbc.queryForObject("""
                select count(*) from vocab_word_sources s
                  join vocab_words w on w.id = s.word_id
                 where w.user_id = ?
                """, Integer.class, userId);
    }

    private String login(String base, String email, String password) throws Exception {
        HttpResponse<String> response = postRaw(base, null, "/api/auth/login",
                Map.of("email", email, "password", password));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private java.util.UUID me(String base, String cookie) throws Exception {
        HttpResponse<String> response = send(base, cookie, "GET", "/api/auth/me", null);
        return java.util.UUID.fromString(MAPPER.readTree(response.body()).get("id").asString());
    }

    private JsonNode post(String base, String cookie, String path, Map<String, ?> body) throws Exception {
        HttpResponse<String> response = postRaw(base, cookie, path, body);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> postRaw(String base, String cookie, String path, Map<String, ?> body)
            throws Exception {
        return send(base, cookie, "POST", path, body);
    }

    private HttpResponse<String> send(String base, String cookie, String method, String path,
                                      Map<String, ?> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN");
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(
                    body == null ? "" : MAPPER.writeValueAsString(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
