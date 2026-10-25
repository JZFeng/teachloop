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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T453 / AC-69.5 验收：导入时指定词单名。
 *
 * <p>覆盖三件事：① 词单不存在则自动创建，这一批词全部挂上；② 同名词单已存在时复用，
 * 引用已在就不重复计数（幂等）；③ **达词单上限时导入照常完成**，该批不入词单，
 * 响应里给 {@code skippedReason=list-limit} 供界面提示。
 *
 * <p>上限压到 1，一个测试方法里就能同时验「创建」与「超限跳过」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false",
                "teachloop.vocab.lists.max-per-student=1"
        })
@Testcontainers
class ImportToListTest {

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

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("T453 导入带词单名：自动建单 → 复用幂等 → 达上限时导入继续但不入单")
    void importAttachesToList() throws Exception {
        String base = base();
        String cookie = login(base, "student@teachloop.local");
        wipeLists(base, cookie);

        // ① 词单不存在：自动创建，三个词都挂上
        JsonNode first = post(base, cookie, "/api/vocab/import/commit", Map.of(
                "text", "zzqqaa, zzqqbb, zzqqcc", "language", "en", "listName", "导入生词"));
        assertThat(first.get("counts").get("created").asInt()).isEqualTo(3);
        JsonNode firstList = first.get("list");
        assertThat(firstList.get("name").asString()).isEqualTo("导入生词");
        assertThat(firstList.get("created").asBoolean()).isTrue();
        assertThat(firstList.get("added").asInt()).isEqualTo(3);
        assertThat(firstList.get("skippedReason").isNull()).as("没超限，不应有跳过原因").isTrue();

        String listId = firstList.get("listId").asString();
        JsonNode page = get(base, cookie, "/api/vocab/lists/" + listId + "/words?size=50");
        assertThat(page.get("total").asInt()).isEqualTo(3);

        // ② 再导入同一批：词已存在、引用已在 → 复用词单，added=0、duplicated=3
        JsonNode second = post(base, cookie, "/api/vocab/import/commit", Map.of(
                "text", "zzqqaa, zzqqbb, zzqqcc", "language", "en", "listName", "导入生词"));
        assertThat(second.get("list").get("created").asBoolean()).isFalse();
        assertThat(second.get("list").get("added").asInt()).isZero();
        assertThat(second.get("list").get("duplicated").asInt()).isEqualTo(3);

        // ③ 已有 1 份词单（上限=1），换名导入：词照常入库，该批不入词单
        JsonNode third = post(base, cookie, "/api/vocab/import/commit", Map.of(
                "text", "zzqqdd", "language", "en", "listName", "第二份"));
        assertThat(third.get("counts").get("created").asInt()).as("导入不被词单上限打断").isEqualTo(1);
        assertThat(third.get("list").get("skippedReason").asString()).isEqualTo("list-limit");
        assertThat(third.get("list").get("listId").isNull()).isTrue();
        assertThat(get(base, cookie, "/api/vocab/lists")).as("没有偷偷建出第二份").hasSize(1);
    }

    private void wipeLists(String base, String cookie) throws Exception {
        for (JsonNode row : get(base, cookie, "/api/vocab/lists")) {
            delete(base, cookie, "/api/vocab/lists/" + row.get("id").asString());
        }
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private JsonNode get(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, "GET", path, null);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private JsonNode post(String base, String cookie, String path, Object body) throws Exception {
        HttpResponse<String> response = send(base, cookie, "POST", path, body);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private JsonNode delete(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, "DELETE", path, null);
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
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
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
