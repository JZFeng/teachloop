package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/**
 * T396 集成验收：来源多选并集、状态/收藏/标签/卡组交集、搜索、分页排序跨页、语言隔离、URL 参数还原。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false"
        })
@Testcontainers
class VocabWordsQueryTest {

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
    @DisplayName("筛选：来源多选取并集；状态/收藏/标签/卡组/搜索取交集；暂停词仍在列表")
    void filters() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        UUID userId = me(base, cookie);
        String batch1 = commit(base, cookie, "alpha, beta, gamma", "Unit 5");
        String batch2 = commit(base, cookie, "delta", "Unit 6");

        jdbc.update("update vocab_words set favorite = true, tags = '[\"exam\"]'::jsonb"
                + " where user_id = ? and normalized_form = 'alpha'", userId);
        jdbc.update("update vocab_progress set state = 'REVIEW', due_at = now() + interval '1 day'"
                + " where word_id = (select id from vocab_words where user_id = ? and normalized_form = 'alpha')",
                userId);
        jdbc.update("update vocab_words set removed = true where user_id = ? and normalized_form = 'beta'",
                userId);

        JsonNode all = get(base, cookie, "/api/vocab/words");
        assertThat(all.get("total").asInt()).isEqualTo(4);
        JsonNode lastItem = all.get("items").get(all.get("items").size() - 1);
        assertThat(lastItem.get("form").asString()).isEqualTo("alpha");
        assertThat(lastItem.get("state").asString()).isEqualTo("REVIEW");
        boolean betaPaused = false;
        for (JsonNode item : all.get("items")) {
            if ("beta".equals(item.get("form").asString())) {
                betaPaused = item.get("paused").asBoolean();
            }
        }
        assertThat(betaPaused).isTrue();

        assertThat(get(base, cookie, "/api/vocab/words?q=alp").get("total").asInt()).isEqualTo(1);
        assertThat(get(base, cookie, "/api/vocab/words?state=REVIEW").get("total").asInt()).isEqualTo(1);
        assertThat(get(base, cookie, "/api/vocab/words?source=IMPORT:" + batch1)
                .get("total").asInt()).isEqualTo(3);
        assertThat(get(base, cookie, "/api/vocab/words?source=IMPORT:" + batch1
                + "&source=IMPORT:" + batch2).get("total").asInt()).isEqualTo(4);
        assertThat(get(base, cookie, "/api/vocab/words?favorite=true").get("total").asInt()).isEqualTo(1);
        assertThat(get(base, cookie, "/api/vocab/words?tag=exam").get("total").asInt()).isEqualTo(1);

        UUID deckId = UUID.randomUUID();
        jdbc.update("""
                insert into vocab_decks (id, user_id, name, criteria, position, deleted, created_at, updated_at)
                values (?, ?, '复习中的词', '{"states":["REVIEW"]}'::jsonb, 0, false, now(), now())
                """, deckId, userId);
        assertThat(get(base, cookie, "/api/vocab/words?deckId=" + deckId).get("total").asInt())
                .isEqualTo(1);

        // URL 参数即筛选条件：非法分页大小明确 400
        HttpResponse<String> badSize = send(base, cookie, "/api/vocab/words?size=10");
        assertThat(badSize.statusCode()).isEqualTo(400);

        // 学习语言隔离：zh 词不进 en 列表
        HttpResponse<String> zhCommit = post(base, cookie, "/api/vocab/import/commit",
                Map.of("text", "苹果", "language", "zh"));
        assertThat(zhCommit.statusCode()).isEqualTo(200);
        assertThat(get(base, cookie, "/api/vocab/words").get("total").asInt()).isEqualTo(4);
        assertThat(get(base, cookie, "/api/vocab/words?language=zh").get("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("排序跨分页：25 个词按 form 升序，20/5 两页拼起来仍是有序的")
    void sortingWorksAcrossPages() throws Exception {
        String base = base();
        String cookie = login(base, "teacher@teachloop.local");
        StringBuilder text = new StringBuilder();
        for (int index = 1; index <= 25; index++) {
            text.append(String.format("pageword%02d", index)).append('\n');
        }
        HttpResponse<String> response = post(base, cookie, "/api/vocab/import/commit",
                Map.of("text", text.toString(), "language", "en"));
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode first = get(base, cookie, "/api/vocab/words?q=pageword&sort=form&dir=asc&size=20&page=1");
        JsonNode second = get(base, cookie, "/api/vocab/words?q=pageword&sort=form&dir=asc&size=20&page=2");
        assertThat(first.get("total").asInt()).isEqualTo(25);
        assertThat(first.get("items").size()).isEqualTo(20);
        assertThat(second.get("items").size()).isEqualTo(5);
        List<String> forms = new ArrayList<>();
        first.get("items").forEach(item -> forms.add(item.get("form").asString()));
        second.get("items").forEach(item -> forms.add(item.get("form").asString()));
        assertThat(forms).isSorted();
        assertThat(forms.get(0)).isEqualTo("pageword01");
        assertThat(forms.get(24)).isEqualTo("pageword25");
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private String commit(String base, String cookie, String text, String label) throws Exception {
        HttpResponse<String> response = post(base, cookie, "/api/vocab/import/commit",
                Map.of("text", text, "language", "en", "label", label));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("batchId").asString();
    }

    private UUID me(String base, String cookie) throws Exception {
        HttpResponse<String> response = send(base, cookie, "/api/auth/me");
        return UUID.fromString(MAPPER.readTree(response.body()).get("id").asString());
    }

    private JsonNode get(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, path);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> send(String base, String cookie, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN").GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String base, String cookie, String path, Map<String, ?> body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("Accept-Language", "zh-CN")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String base, String email) throws Exception {
        HttpResponse<String> response = post(base, null, "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
