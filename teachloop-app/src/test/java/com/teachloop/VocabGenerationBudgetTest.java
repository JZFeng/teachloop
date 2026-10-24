package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

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
import tools.jackson.databind.ObjectMapper;

/** T394 验收：日预算打满时暂停入队（词条保持 PENDING，不建 job）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.engine=stub",
                "teachloop.vocab.generation.poll-ms=3600000",
                "teachloop.vocab.generation.daily-budget=0"
        })
@Testcontainers
class VocabGenerationBudgetTest {

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
    @DisplayName("daily-budget=0：导入成功但一个 job 都不建，词条仍 PENDING 可复习")
    void budgetStopsEnqueue() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String cookie = login(base);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(base + "/api/vocab/import/commit"))
                        .header("Content-Type", "application/json")
                        .header("Accept-Language", "zh-CN")
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(
                                Map.of("text", "budgeted", "language", "en"))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        UUID wordId = jdbc.queryForObject(
                "select id from vocab_words where normalized_form = 'budgeted'", UUID.class);
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_generation_jobs where subject_id = ?", Integer.class, wordId))
                .isZero();
        assertThat(jdbc.queryForObject("select content_status from vocab_words where id = ?",
                String.class, wordId)).isEqualTo("PENDING");
    }

    private String login(String base) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "email", "student@teachloop.local", "password", "teachloop123"));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(base + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
