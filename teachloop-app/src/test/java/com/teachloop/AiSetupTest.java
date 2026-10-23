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

/** 管理员初始设置：首个管理员 + AI 配置保存/掩码；Key 不落明文。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AiSetupTest {

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
        r.add("teachloop.security.secret", () -> "test-secret-for-ai-settings");
    }

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("首个管理员初始化；AI Key 加密保存且只返回掩码；非管理员 403")
    void setupAdminAndAiSettings() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");

        Map<String, Object> status = Json.parse(request(base, null, "GET", "/api/setup/status", null).body());
        assertThat(status.get("needsAdmin")).isEqualTo(true);
        assertThat(status.get("aiConfigured")).isEqualTo(false);

        HttpResponse<String> created = request(base, null, "POST", "/api/setup/admin",
                Map.of("email", "first-admin@teachloop.local", "password", "first-admin-pass",
                        "displayName", "初始管理员", "apiKey", "sk-test-abcd1234"));
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(200);
        assertThat(Json.parse(created.body()).get("role")).isEqualTo("ADMIN");
        String admin = cookie(created);

        assertThat(request(base, null, "POST", "/api/setup/admin",
                Map.of("email", "second@teachloop.local", "password", "second-pass-123")).statusCode())
                .isEqualTo(409);

        Map<String, Object> saved = Json.parse(request(base, admin, "PUT", "/api/admin/ai-settings",
                Map.of("provider", "deepseek", "baseUrl", "https://api.deepseek.com",
                        "model", "deepseek-flash", "apiKey", "sk-test-abcd1234", "temperature", 0.3)).body());
        assertThat(saved.get("apiKeySet")).isEqualTo(true);
        assertThat(saved.get("apiKeyMasked")).isEqualTo("sk-****1234");
        assertThat(saved.get("restartRequired")).isEqualTo(false);
        assertThat(saved.toString()).doesNotContain("sk-test-abcd1234");

        Map<String, Object> read = Json.parse(request(base, admin, "GET", "/api/admin/ai-settings", null).body());
        assertThat(read.get("apiKeySet")).isEqualTo(true);
        assertThat(read.toString()).doesNotContain("sk-test-abcd1234");

        String cipher = jdbc.queryForObject("select api_key_cipher from ai_settings where id = 1", String.class);
        assertThat(cipher).isNotBlank().doesNotContain("sk-test-abcd1234");

        String teacher = login(base, "teacher@teachloop.local");
        assertThat(request(base, teacher, "GET", "/api/admin/ai-settings", null).statusCode()).isEqualTo(403);

        Map<String, Object> after = Json.parse(request(base, null, "GET", "/api/setup/status", null).body());
        assertThat(after.get("needsAdmin")).isEqualTo(false);
        assertThat(after.get("aiConfigured")).isEqualTo(true);

        // D-30：管理员维护全局 provider；第一个 provider 自动成为当前模型。
        Map<String, Object> provider = Json.parse(request(base, admin, "POST", "/api/admin/ai-providers",
                Map.of("providerId", "local", "displayName", "Local Qwen",
                        "baseUrl", "http://127.0.0.1:8080/v1", "protocol", "openai",
                        "apiKey", "sk-provider-9999", "model", "qwen3.8")).body());
        assertThat(provider.get("providerId")).isEqualTo("local");
        assertThat(provider.get("active")).isEqualTo(true);
        assertThat(provider.get("apiKeyMasked")).isEqualTo("sk-****9999");
        assertThat(provider.toString()).doesNotContain("sk-provider-9999");
        String providerCipher = jdbc.queryForObject(
                "select api_key_cipher from ai_providers where provider_key = 'local'", String.class);
        assertThat(providerCipher).isNotBlank().doesNotContain("sk-provider-9999");

        // 教师只看到下拉选项并切换全局模型；学生 403。
        Map<String, Object> options = Json.parse(request(base, teacher, "GET", "/api/ai-models", null).body());
        assertThat(options.get("currentProviderId")).isEqualTo("local");
        assertThat(options.toString()).contains("Local Qwen");
        Map<String, Object> switched = Json.parse(request(base, teacher, "PUT", "/api/ai-models/current",
                Map.of("providerId", "local")).body());
        assertThat(switched.get("currentProviderId")).isEqualTo("local");

        String student = login(base, "student@teachloop.local");
        assertThat(request(base, student, "GET", "/api/ai-models", null).statusCode()).isEqualTo(403);
    }

    private String login(String base, String email) throws Exception {
        return cookie(request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123")));
    }

    private static String cookie(HttpResponse<String> res) {
        assertThat(res.statusCode()).isEqualTo(200);
        return res.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
    }

    private HttpResponse<String> request(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (cookie != null) b.header("Cookie", cookie);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static String write(Object o) { return MAPPER.writeValueAsString(o); }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) { return MAPPER.readValue(s, Map.class); }
    }
}
