package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.tts.StubTtsEngine;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.ObjectMapper;

/**
 * 019 / AC-57.8：{@code teachloop.vocab.tts.sentences.enabled=false} 时例句朗读整体关闭，
 * 但单词发音完全不受影响。
 *
 * <p>与 {@link VocabTtsTest} 分开成两个上下文，因为开关是构造注入的配置值，改不了运行中的 Bean。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false",
                "teachloop.vocab.tts.engine=stub",
                "teachloop.vocab.tts.provider=local",
                "teachloop.vocab.tts.sentences.enabled=false"
        })
@Testcontainers
class VocabTtsSentenceDisabledTest {

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
    StubTtsEngine engine;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("关掉例句朗读：text 返回 404 且不调引擎，form 仍 200，config 说 false")
    void sentencesOffButWordsStillWork() throws Exception {
        String base = base();
        String cookie = login(base);
        String probe = "ttsoff" + UUID.randomUUID().toString().replace("-", "");
        int before = engine.calls();

        HttpResponse<byte[]> sentence = send(base, cookie,
                "/api/vocab/audio?text=" + URLEncoder.encode("disabled probe " + probe,
                        StandardCharsets.UTF_8) + "&lang=en");
        assertThat(sentence.statusCode()).isEqualTo(404);
        // 开关在合成之前生效：关掉后不再浪费 CPU，也不往 BlobStore 写
        assertThat(engine.calls()).isEqualTo(before);

        // 单词发音不受开关影响（AC-57.8 明确要求）
        HttpResponse<byte[]> word = send(base, cookie, "/api/vocab/audio?form=" + probe);
        assertThat(word.statusCode()).isEqualTo(200);
        assertThat(word.body()).isNotEmpty();
        assertThat(engine.calls()).isEqualTo(before + 1);

        HttpResponse<String> config = sendText(base, cookie, "/api/vocab/config");
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(config.body()).get("sentenceAudio").asBoolean()).isFalse();
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private HttpResponse<byte[]> send(String base, String cookie, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN").GET();
        if (cookie != null) builder.header("Cookie", cookie);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> sendText(String base, String cookie, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN").GET();
        if (cookie != null) builder.header("Cookie", cookie);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String base) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(Map.of(
                                "email", "student@teachloop.local", "password", "teachloop123"))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
