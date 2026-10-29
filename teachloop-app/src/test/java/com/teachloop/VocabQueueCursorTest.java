package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** T556 验收：队列游标 `after=<epochMilli>:<id>` 不重不漏；`newServed` 让配额接着算（AC-89.1/89.4）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false",
                "teachloop.vocab.review.daily-new-cards=3",
                "teachloop.grading.poll-ms=3600000",
                "teachloop.grading.dispatch-ms=3600000",
                "teachloop.grading.sweep-ms=3600000"
        })
@Testcontainers
class VocabQueueCursorTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired
    Environment env;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("游标翻页不重不漏；newServed 扣减新卡配额")
    void cursorPagesWithoutOverlap() throws Exception {
        String base = base();
        String cookie = login(base);
        UUID userId = UUID.fromString(MAPPER.readTree(get(base, cookie, "/api/auth/me").body())
                .get("id").asString());
        commit(base, cookie, "alpha, beta, gamma, delta, epsilon");
        // 全部改成到期复习卡，绕开新卡上限对翻页的干扰
        jdbc.update("""
                update vocab_progress set state = 'REVIEW', due_at = now() - interval '1 day'
                 where user_id = ?
                """, userId);

        JsonNode first = MAPPER.readTree(get(base, cookie, "/api/vocab/review/queue?limit=2").body());
        assertThat(first.get("items").size()).isEqualTo(2);

        Set<String> seen = new HashSet<>();
        first.get("items").forEach(item -> seen.add(item.get("id").asString()));

        JsonNode last = first.get("items").get(first.get("items").size() - 1);
        // 用原始 ISO 串拼游标：截到毫秒会丢微秒，导致同一行在下一页重复出现
        String cursor = last.get("dueAt").asString() + "|" + last.get("id").asString();
        JsonNode second = MAPPER.readTree(get(base, cookie,
                "/api/vocab/review/queue?limit=2&after=" + java.net.URLEncoder.encode(cursor, "UTF-8")).body());

        assertThat(second.get("items").size()).isGreaterThan(0);
        for (JsonNode item : second.get("items")) {
            assertThat(seen).doesNotContain(item.get("id").asString());
        }

        // newServed 把已出的新卡扣掉：3 张配额发完后再要就是 0
        JsonNode fresh = MAPPER.readTree(get(base, cookie, "/api/vocab/review/queue").body());
        assertThat(fresh.get("newToday").asInt()).isZero();   // 已全部改成复习卡
        JsonNode exhausted = MAPPER.readTree(get(base, cookie,
                "/api/vocab/review/queue?newServed=3").body());
        assertThat(exhausted.get("newToday").asInt()).isZero();
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private void commit(String base, String cookie, String text) throws Exception {
        HttpResponse<String> response = send(base, cookie, "POST", "/api/vocab/import/commit",
                Map.of("text", text, "language", "en", "label", "Cursor unit"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private HttpResponse<String> get(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, "GET", path, null);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return response;
    }

    private HttpResponse<String> send(String base, String cookie, String method, String path, Object body)
            throws Exception {
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

    private String login(String base) throws Exception {
        HttpResponse<String> response = send(base, null, "POST", "/api/auth/login",
                Map.of("email", "student@teachloop.local", "password", "teachloop123"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
