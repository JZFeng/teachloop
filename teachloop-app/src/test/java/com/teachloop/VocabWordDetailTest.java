package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.content.ContentStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** T399 集成验收：详情两种状态、加入词库当天进队列、重复加入不新建、反馈落库带 word id、越权 404。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false"
        })
@Testcontainers
class VocabWordDetailTest {

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
    ContentStore contentStore;

    private final HttpClient client = HttpClient.newHttpClient();

    /** T431：内容只存全局 ContentStore，详情按 content_id 解析（v1 形状原样返回）。 */
    private static final String CONTENT_V1 = """
            {"senses":[{"pos":"n.","zh":"详情释义","en":"detail sense"}],
             "examples":[{"en":"A detail sentence.","zh":"一条详情例句。"}]}
            """;

    @Test
    @DisplayName("不在库 → 加入 → 在库（NEW、当天到期）→ 重复加入不新建 → 反馈落库")
    void detailLifecycle() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);

        JsonNode before = get(base, cookie, "/api/vocab/words/by-form/Serendipity");
        assertThat(before.get("inLibrary").asBoolean()).isFalse();
        assertThat(before.get("form").asString()).isEqualTo("Serendipity");
        assertThat(before.get("family").isNull()).isTrue();

        JsonNode added = MAPPER.readTree(post(base, cookie, "/api/vocab/words",
                Map.of("form", "Serendipity", "language", "en")).body());
        assertThat(added.get("created").asInt()).isEqualTo(1);
        UUID wordId = UUID.fromString(added.get("id").asString());

        JsonNode after = get(base, cookie, "/api/vocab/words/by-form/serendipity");
        assertThat(after.get("inLibrary").asBoolean()).isTrue();
        assertThat(after.get("state").asString()).isEqualTo("NEW");
        assertThat(after.get("dueAt").isNull()).isFalse();
        assertThat(after.get("paused").asBoolean()).isFalse();
        assertThat(after.get("language").asString()).isEqualTo("en");
        // T431：还没内容 → content 为 null（页面显示「未准备」），不抛
        assertThat(after.get("content").isNull()).isTrue();

        // T431：内容写全局 ContentStore，用户行只存 content_id；详情按 id 解析出内容
        long contentId = contentStore.put("en", "serendipity", CONTENT_V1, "TEST");
        jdbc.update("update vocab_words set content_id = ?, content_status = 'READY' where id = ?",
                contentId, wordId);
        JsonNode withContent = get(base, cookie, "/api/vocab/words/by-form/serendipity");
        assertThat(withContent.get("contentStatus").asString()).isEqualTo("READY");
        assertThat(withContent.get("content").path("senses").get(0).path("zh").asString())
                .isEqualTo("详情释义");
        assertThat(withContent.get("content").path("examples").get(0).path("en").asString())
                .isEqualTo("A detail sentence.");

        JsonNode again = MAPPER.readTree(post(base, cookie, "/api/vocab/words",
                Map.of("form", "SERENDIPITY", "language", "en")).body());
        assertThat(again.get("created").asInt()).isZero();
        assertThat(again.get("duplicated").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_words where user_id = ? and normalized_form = 'serendipity'",
                Integer.class, userId)).isEqualTo(1);

        JsonNode feedback = MAPPER.readTree(post(base, cookie,
                "/api/vocab/words/" + wordId + "/feedback",
                Map.of("note", "例句太长")).body());
        assertThat(feedback.get("id").asInt()).isPositive();
        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_feedback
                 where user_id = ? and word_id = ? and status = 'OPEN'
                """, Integer.class, userId, wordId)).isEqualTo(1);
    }

    @Test
    @DisplayName("详情归属：别的用户看同一个词是「不在库」，不能给别人的词反馈（404）")
    void ownership() throws Exception {
        String base = base();
        String studentCookie = login(base, "student@teachloop.local");
        JsonNode added = MAPPER.readTree(post(base, studentCookie, "/api/vocab/words",
                Map.of("form", "ownershipword", "language", "en")).body());
        UUID wordId = UUID.fromString(added.get("id").asString());

        String teacherCookie = login(base, "teacher@teachloop.local");
        assertThat(get(base, teacherCookie, "/api/vocab/words/by-form/ownershipword")
                .get("inLibrary").asBoolean()).isFalse();
        assertThat(post(base, teacherCookie, "/api/vocab/words/" + wordId + "/feedback",
                Map.of("note", "x")).statusCode()).isEqualTo(404);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private UUID me(String base, String cookie) throws Exception {
        return UUID.fromString(MAPPER.readTree(send(base, cookie, "GET", "/api/auth/me", null).body())
                .get("id").asString());
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
