package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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

/** T130：同一错误 code 不随语言变，message 随 Accept-Language 变。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class I18nErrorTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired
    Environment env;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("T130：404 与 400 的 code 稳定，message 随 Accept-Language 切换")
    void errorMessagesAreLocalized() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base);

        HttpResponse<String> zh404 = request(base, cookie, "GET", "/api/definitely-not-a-route", null, "zh-CN");
        HttpResponse<String> en404 = request(base, cookie, "GET", "/api/definitely-not-a-route", null, "en");
        assertThat(zh404.statusCode()).isEqualTo(404);
        assertThat(en404.statusCode()).isEqualTo(404);
        Map<String, Object> zhBody = Json.parse(zh404.body());
        Map<String, Object> enBody = Json.parse(en404.body());
        assertThat(zhBody.get("code")).isEqualTo("NOT_FOUND");
        assertThat(enBody.get("code")).isEqualTo("NOT_FOUND");
        assertThat(zhBody.get("message").toString()).isNotEqualTo(enBody.get("message").toString());
        assertThat(zhBody.get("message").toString()).contains("接口不存在");
        assertThat(enBody.get("message").toString()).contains("not found");

        HttpResponse<String> zh400 = request(base, cookie, "POST", "/api/papers", Map.of(), "zh-CN");
        HttpResponse<String> en400 = request(base, cookie, "POST", "/api/papers", Map.of(), "en");
        assertThat(zh400.statusCode()).isEqualTo(400);
        assertThat(en400.statusCode()).isEqualTo(400);
        Map<String, Object> zhBad = Json.parse(zh400.body());
        Map<String, Object> enBad = Json.parse(en400.body());
        assertThat(zhBad.get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(enBad.get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(zhBad.get("message").toString()).isNotEqualTo(enBad.get("message").toString());
    }

    private String login(String base) throws Exception {
        HttpResponse<String> res = request(base, null, "POST", "/api/auth/login",
                Map.of("email", "teacher@teachloop.local", "password", "teachloop123"), "zh-CN");
        assertThat(res.statusCode()).isEqualTo(200);
        List<String> cookies = res.headers().allValues("set-cookie");
        return cookies.stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
    }

    private HttpResponse<String> request(String base, String cookie, String method, String path,
                                         Object body, String lang) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("Accept-Language", lang);
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static String write(Object o) {
            return MAPPER.writeValueAsString(o);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) {
            return MAPPER.readValue(s, Map.class);
        }
    }
}
