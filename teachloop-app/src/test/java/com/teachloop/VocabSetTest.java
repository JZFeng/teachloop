package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

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

/** T406 验收：词集 CRUD、批量启停/删除、词条顺序、移除只移出词集、非教师 403。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false"
        })
@Testcontainers
class VocabSetTest {

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
    @DisplayName("创建/重名 409/加词去重/排序/移出/批量启停/软删/学生 403")
    void setLifecycle() throws Exception {
        String base = base();
        String cookie = login(base, "teacher@teachloop.local");

        JsonNode created = post(base, cookie, "/api/vocab/sets",
                Map.of("name", "Unit 5 生词", "language", "en", "description", "课前预习"));
        UUID setId = UUID.fromString(created.get("id").asString());
        assertThat(created.get("wordCount").asInt()).isZero();
        assertThat(postRaw(base, cookie, "/api/vocab/sets",
                Map.of("name", "Unit 5 生词")).statusCode()).isEqualTo(409);

        String longWord = "a".repeat(65);
        JsonNode added = post(base, cookie, "/api/vocab/sets/" + setId + "/words",
                Map.of("words", List.of("apple", "bravo", "Apple", longWord)));
        assertThat(added.get("added").asInt()).isEqualTo(2);
        assertThat(added.get("skipped").asInt()).isEqualTo(2);

        JsonNode detail = get(base, cookie, "/api/vocab/sets/" + setId);
        assertThat(detail.get("set").get("wordCount").asInt()).isEqualTo(2);
        JsonNode words = detail.get("words");
        List<String> forms = new java.util.ArrayList<>();
        words.forEach(word -> forms.add(word.get("form").asString()));
        assertThat(forms).containsExactly("apple", "bravo");
        UUID appleId = UUID.fromString(words.get(0).get("id").asString());
        UUID bravoId = UUID.fromString(words.get(1).get("id").asString());

        put(base, cookie, "/api/vocab/sets/" + setId + "/words/order",
                Map.of("wordIds", List.of(bravoId, appleId)));
        JsonNode reordered = get(base, cookie, "/api/vocab/sets/" + setId);
        assertThat(reordered.get("words").get(0).get("form").asString()).isEqualTo("bravo");

        assertThat(delete(base, cookie, "/api/vocab/sets/" + setId + "/words/" + appleId).statusCode())
                .isEqualTo(200);
        assertThat(get(base, cookie, "/api/vocab/sets/" + setId).get("set").get("wordCount").asInt())
                .isEqualTo(1);

        JsonNode inactive = put(base, cookie, "/api/vocab/sets/" + setId, Map.of("active", false));
        assertThat(inactive.get("active").asBoolean()).isFalse();
        JsonNode batch = post(base, cookie, "/api/vocab/sets/batch",
                Map.of("ids", List.of(setId), "action", "ACTIVATE"));
        assertThat(batch.get("affected").asInt()).isEqualTo(1);
        assertThat(get(base, cookie, "/api/vocab/sets").get(0).get("active").asBoolean()).isTrue();

        assertThat(delete(base, cookie, "/api/vocab/sets/" + setId).statusCode()).isEqualTo(200);
        assertThat(get(base, cookie, "/api/vocab/sets")).isEmpty();
        assertThat(getRaw(base, cookie, "/api/vocab/sets/" + setId).statusCode()).isEqualTo(404);

        String studentCookie = login(base, "student@teachloop.local");
        assertThat(postRaw(base, studentCookie, "/api/vocab/sets",
                Map.of("name", "x")).statusCode()).isEqualTo(403);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private JsonNode get(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = getRaw(base, cookie, path);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> getRaw(String base, String cookie, String path) throws Exception {
        return send(base, cookie, "GET", path, null);
    }

    private JsonNode post(String base, String cookie, String path, Object body) throws Exception {
        HttpResponse<String> response = postRaw(base, cookie, path, body);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> postRaw(String base, String cookie, String path, Object body)
            throws Exception {
        return send(base, cookie, "POST", path, body);
    }

    private JsonNode put(String base, String cookie, String path, Object body) throws Exception {
        HttpResponse<String> response = send(base, cookie, "PUT", path, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> delete(String base, String cookie, String path) throws Exception {
        return send(base, cookie, "DELETE", path, null);
    }

    private HttpResponse<String> send(String base, String cookie, String method, String path,
                                      Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN");
        if (cookie != null) builder.header("Cookie", cookie);
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(
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
