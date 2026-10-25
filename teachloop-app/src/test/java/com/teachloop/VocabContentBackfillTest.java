package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teachloop.vocab.content.ContentBackfillRunner;
import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.content.StoredContent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
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
 * T431 验收：V063 把存量内容搬进 legacy 表后，启动回填把内容写进全局 delta、回填
 * {@code content_id}、删除 legacy 表；再次运行无操作。读路径（详情/队列/会话当前卡）
 * 按 content_id 解析内容，并带词条 language 供前端选例句音色。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false",
                "teachloop.vocab.content.backfill-on-startup=true"
        })
@Testcontainers
class VocabContentBackfillTest {

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
    private static final String LEGACY = "vocab_words_content_legacy";

    private static final String CONTENT = """
            {"senses":[{"pos":"n.","zh":"回填释义","en":"backfilled sense"}],
             "examples":[{"en":"The backfill worked.","zh":"回填成功了。"}],
             "analysis":"回填内容原样保留","family":["backfillword"],
             "phonetic":{"uk":"/bæk/","us":"/bæk/"}}
            """;

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ContentBackfillRunner runner;

    @Autowired
    ContentStore contentStore;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("回填：legacy 内容写入 delta + content_id 回填 + legacy 表删除；再跑一次无操作；读路径按 content_id 出内容")
    void backfillsLegacyContentOnce() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        UUID wordId = insertWord(userId, "backfillword", "PENDING");
        assertThat(contentId(wordId)).isNull();

        resetLegacyTable();
        jdbc.update("""
                insert into vocab_words_content_legacy (id, language, normalized_form, content)
                values (?, 'en', 'backfillword', cast(? as jsonb))
                """, wordId, CONTENT);
        assertThat(legacyExists()).isTrue();

        runner.run(null);

        Long contentId = contentId(wordId);
        assertThat(contentId).as("回填后用户行拿到全局 content_id").isNotNull();
        assertThat(contentStore.getById(contentId)).get()
                .extracting(StoredContent::json).asString()
                .contains("回填释义")
                .contains("The backfill worked.");
        assertThat(legacyExists()).as("全部成功后删除 legacy 表").isFalse();

        // 幂等：legacy 表已不存在，再跑一次什么都不做（content_id 不变、不抛）
        runner.run(null);
        assertThat(contentId(wordId)).isEqualTo(contentId);

        // 读路径 1：详情按 content_id 解析，v1 内容原样返回
        JsonNode detail = get(base, cookie, "/api/vocab/words/by-form/backfillword");
        assertThat(detail.get("inLibrary").asBoolean()).isTrue();
        assertThat(detail.path("content").path("senses").get(0).path("zh").asString())
                .isEqualTo("回填释义");
        assertThat(detail.path("content").path("examples").get(0).path("en").asString())
                .isEqualTo("The backfill worked.");

        // 读路径 2：今日队列带 content + language（前端按 language 选例句音色）
        JsonNode queueItem = itemInQueue(base, cookie, "backfillword");
        assertThat(queueItem.get("language").asString()).isEqualTo("en");
        assertThat(queueItem.path("content").path("senses").get(0).path("zh").asString())
                .isEqualTo("回填释义");

        // 读路径 3：自由复习当前卡带 content + language
        UUID sessionId = createSession(base, cookie, wordId);
        JsonNode current = get(base, cookie, "/api/vocab/review-sessions/" + sessionId + "/current");
        assertThat(current.get("language").asString()).isEqualTo("en");
        assertThat(current.path("content").path("senses").get(0).path("zh").asString())
                .isEqualTo("回填释义");
    }

    @Test
    @DisplayName("v2 内容经读路径返回时适配成前端 v1 形状（内容包/v2 delta 与前端之间的过渡层）")
    void adaptsSchemaV2OnReadPath() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        UUID wordId = insertWord(userId, "v2word", "PENDING");

        long contentId = contentStore.put("en", "v2word", """
                {"schemaVersion":2,"word":"v2word","language":"en",
                 "basicInfo":{"pronunciation":{"uk":"/viː/","us":"/viː/"},
                              "partOfSpeech":["verb","noun"]},
                 "meanings":{"zh":"v2 释义","en":"v2 meaning"},
                 "examples":[
                   {"text":"Second example.","translations":{"zh":"第二句"},"popularity":2},
                   {"text":"First example.","translations":{"zh":"第一句"},"popularity":1}],
                 "wordFormation":{"analysis":"v2 构词"},
                 "wordFamily":{"items":[{"word":"v2words"},{"word":"v2words"}]}}
                """, "TEST");
        jdbc.update("update vocab_words set content_id = ?, content_status = 'READY' where id = ?",
                contentId, wordId);

        JsonNode content = get(base, cookie, "/api/vocab/words/by-form/v2word").path("content");
        assertThat(content.path("senses").get(0).path("pos").asString()).isEqualTo("verb, noun");
        assertThat(content.path("senses").get(0).path("zh").asString()).isEqualTo("v2 释义");
        assertThat(content.path("examples").get(0).path("en").asString()).isEqualTo("First example.");
        assertThat(content.path("examples").get(0).path("zh").asString()).isEqualTo("第一句");
        assertThat(content.path("analysis").asString()).isEqualTo("v2 构词");
        assertThat(content.path("family").size()).isEqualTo(1);
        assertThat(content.path("phonetic").path("uk").asString()).isEqualTo("/viː/");
        assertThat(content.has("schemaVersion")).as("前端过渡期只认 v1 字段").isFalse();
    }

    @Test
    @DisplayName("单行失败：抛 IllegalStateException（启动失败）并保留 legacy 表，下次启动可重试")
    void failureKeepsLegacyTable() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        UUID wordId = insertWord(userId, "brokenbackfill", "PENDING");

        resetLegacyTable();
        // normalized_form 为空 → ContentStore.put 抛 IllegalArgument（form is required）
        jdbc.update("""
                insert into vocab_words_content_legacy (id, language, normalized_form, content)
                values (?, 'en', '', cast(? as jsonb))
                """, wordId, CONTENT);

        try {
            assertThatThrownBy(() -> runner.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("回填失败");
            assertThat(legacyExists()).as("失败时保留 legacy 表供下次启动重试").isTrue();
            assertThat(contentId(wordId)).as("失败行不回填 content_id").isNull();
        } finally {
            jdbc.execute("drop table if exists " + LEGACY);
        }
    }

    private UUID insertWord(UUID userId, String form, String status) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into vocab_words (id, user_id, language, form, normalized_form, content_status,
                                         removed, deleted, created_at, updated_at)
                values (?, ?, 'en', ?, ?, ?, false, false, ?, ?)
                """, id, userId, form, form, status, now, now);
        jdbc.update("""
                insert into vocab_progress (word_id, user_id, state, due_at, interval_days, ease,
                                            lapses, reviewed_count, updated_at)
                values (?, ?, 'NEW', ?, 0, 2.50, 0, 0, ?)
                """, id, userId, now, now);
        return id;
    }

    /** 启动时 runner 已处理并删掉 V063 建的空 legacy 表；测试手动重建同构表。 */
    private void resetLegacyTable() {
        jdbc.execute("drop table if exists " + LEGACY);
        jdbc.execute("""
                create table vocab_words_content_legacy
                    (id uuid, language text, normalized_form text, content jsonb)
                """);
    }

    private boolean legacyExists() {
        return jdbc.queryForObject("select to_regclass(?)", String.class, "public." + LEGACY) != null;
    }

    private Long contentId(UUID wordId) {
        return jdbc.queryForObject("select content_id from vocab_words where id = ?",
                Long.class, wordId);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private UUID me(String base, String cookie) throws Exception {
        return UUID.fromString(MAPPER.readTree(send(base, cookie, "GET", "/api/auth/me", null).body())
                .get("id").asString());
    }

    private JsonNode itemInQueue(String base, String cookie, String form) throws Exception {
        JsonNode queue = get(base, cookie, "/api/vocab/review/queue");
        for (JsonNode item : queue.get("items")) {
            if (form.equals(item.get("form").asString())) {
                return item;
            }
        }
        throw new AssertionError(form + " not in review queue: " + queue);
    }

    private UUID createSession(String base, String cookie, UUID wordId) throws Exception {
        HttpResponse<String> response = send(base, cookie, "POST", "/api/vocab/review-sessions",
                Map.of("wordIds", java.util.List.of(wordId.toString()), "fromStart", true));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return UUID.fromString(MAPPER.readTree(response.body()).get("id").asString());
    }

    private JsonNode get(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, "GET", path, null);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
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
