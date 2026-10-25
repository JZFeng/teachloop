package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.tts.StubTtsEngine;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** T410 验收：首播调引擎并缓存、二次播放不调引擎、长期缓存头、只读单词、未知 key 404。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false",
                "teachloop.vocab.tts.engine=stub",
                "teachloop.vocab.tts.provider=local"
        })
@Testcontainers
class VocabTtsTest {

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
    @DisplayName("首播生成并缓存；二次播放命中缓存不再调引擎；缓存头正确；只读单词")
    void cacheAndHeaders() throws Exception {
        String base = base();
        String cookie = login(base);
        int before = engine.calls();
        // 本地缓存 ./uploads 跨测试运行保留：用唯一词形，避免首播被上次的缓存命中
        String form = "ttsprobe" + UUID.randomUUID().toString().replace("-", "");

        HttpResponse<byte[]> first = send(base, cookie, "/api/vocab/audio?form=" + form);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).isNotEmpty();
        // stub 必须返回合法 WAV（RIFF 头），不能像旧实现那样把文本字节当音频播成噪音
        assertThat(new String(first.body(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("RIFF");
        assertThat(first.headers().firstValue("Content-Type").orElseThrow()).contains("audio/wav");
        assertThat(first.headers().firstValue("Cache-Control").orElseThrow()).contains("immutable");
        assertThat(first.headers().firstValue("ETag")).isPresent();
        assertThat(engine.calls()).isEqualTo(before + 1);
        assertThat(engine.lastText()).isEqualTo(form);

        String etag = first.headers().firstValue("ETag").orElseThrow().replace("\"", "");
        HttpResponse<byte[]> second = send(base, cookie, "/api/vocab/audio?form=" + form.toUpperCase());
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(engine.calls()).isEqualTo(before + 1);

        HttpResponse<byte[]> byKey = send(base, cookie, "/api/vocab/audio/" + etag);
        assertThat(byKey.statusCode()).isEqualTo(200);
        assertThat(byKey.body()).isEqualTo(first.body());

        assertThat(send(base, cookie, "/api/vocab/audio/deadbeef").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("T427：英音/美音各选各的音色，缓存键互不复用")
    void accentVoices() throws Exception {
        String base = base();
        String cookie = login(base);
        String form = "accentprobe" + UUID.randomUUID().toString().replace("-", "");
        int before = engine.calls();

        HttpResponse<byte[]> uk = send(base, cookie, "/api/vocab/audio?form=" + form + "&accent=uk");
        assertThat(uk.statusCode()).isEqualTo(200);
        assertThat(engine.lastVoice()).isEqualTo("en_GB-alan-medium");
        assertThat(engine.calls()).isEqualTo(before + 1);

        HttpResponse<byte[]> us = send(base, cookie, "/api/vocab/audio?form=" + form + "&accent=us");
        assertThat(us.statusCode()).isEqualTo(200);
        assertThat(engine.lastVoice()).isEqualTo("en_US-amy-medium");
        assertThat(engine.calls()).isEqualTo(before + 2);
        assertThat(us.headers().firstValue("ETag")).isNotEqualTo(uk.headers().firstValue("ETag"));

        // 同一种口音再播命中缓存，不再调引擎
        assertThat(send(base, cookie, "/api/vocab/audio?form=" + form + "&accent=uk").statusCode())
                .isEqualTo(200);
        assertThat(engine.calls()).isEqualTo(before + 2);
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
