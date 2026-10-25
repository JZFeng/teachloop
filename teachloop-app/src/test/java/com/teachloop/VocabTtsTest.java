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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T410 验收：首播调引擎并缓存、二次播放不调引擎、长期缓存头、只读单词、未知 key 404。
 *
 * <p>019 / AC-57.6/57.8/57.9 追加：例句按需合成与缓存、lang 区分键、文本上限 400、form/text 互斥、
 * {@code /api/vocab/config} 默认 {@code sentenceAudio=true}。
 */
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

    @Test
    @DisplayName("019/AC-57.6/57.9：例句首播调引擎并缓存，二次同文本命中不调，键与单词不同")
    void sentenceFirstPlayThenCache() throws Exception {
        String base = base();
        String cookie = login(base);
        // ./uploads 跨测试运行保留：用 UUID 文本，避免首播被上次的缓存命中
        String text = "sentprobe" + UUID.randomUUID().toString().replace("-", "");
        String path = audioText(text, "en");
        int before = engine.calls();

        HttpResponse<byte[]> first = send(base, cookie, path);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).isNotEmpty();
        assertThat(new String(first.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(first.headers().firstValue("Content-Type").orElseThrow()).contains("audio/wav");
        assertThat(first.headers().firstValue("Cache-Control").orElseThrow()).contains("immutable");
        // 首次点击才合成：只调一次引擎，且读的是整句（不是规范化后的单词）
        assertThat(engine.calls()).isEqualTo(before + 1);
        assertThat(engine.lastText()).isEqualTo(text);
        assertThat(engine.lastVoice()).isEqualTo("en_US-amy-medium");

        String etag = first.headers().firstValue("ETag").orElseThrow();
        HttpResponse<byte[]> second = send(base, cookie, path);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.headers().firstValue("ETag").orElseThrow()).isEqualTo(etag);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(engine.calls()).isEqualTo(before + 1);

        // AC-57.9：同一字符串走 form（单词）必须另算一个键，句子与单词不撞键
        HttpResponse<byte[]> asWord = send(base, cookie, "/api/vocab/audio?form=" + text);
        assertThat(asWord.statusCode()).isEqualTo(200);
        assertThat(asWord.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(etag);
        assertThat(engine.calls()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("019/AC-57.9：同一文本不同 lang 缓存键不同，音色按语言切换")
    void sentenceLangChangesKeyAndVoice() throws Exception {
        String base = base();
        String cookie = login(base);
        String text = "langprobe" + UUID.randomUUID().toString().replace("-", "");
        int before = engine.calls();

        HttpResponse<byte[]> en = send(base, cookie, audioText(text, "en"));
        assertThat(en.statusCode()).isEqualTo(200);
        assertThat(engine.lastVoice()).isEqualTo("en_US-amy-medium");

        HttpResponse<byte[]> zh = send(base, cookie, audioText(text, "zh"));
        assertThat(zh.statusCode()).isEqualTo(200);
        assertThat(engine.lastVoice()).isEqualTo("zh_CN-huayan-medium");
        assertThat(zh.headers().firstValue("ETag").orElseThrow())
                .isNotEqualTo(en.headers().firstValue("ETag").orElseThrow());
        assertThat(engine.calls()).isEqualTo(before + 2);

        // 同一语言再播命中缓存，不再调引擎
        send(base, cookie, audioText(text, "zh"));
        assertThat(engine.calls()).isEqualTo(before + 2);

        HttpResponse<byte[]> es = send(base, cookie, audioText(text, "es"));
        assertThat(es.statusCode()).isEqualTo(200);
        assertThat(engine.lastVoice()).isEqualTo("es_ES-davefx-medium");
        assertThat(engine.calls()).isEqualTo(before + 3);
    }

    @Test
    @DisplayName("019/AC-57.9：空文本、超长文本、form+text 互斥、未知 lang 都是 400 且不合成")
    void sentenceRejectsBadInput() throws Exception {
        String base = base();
        String cookie = login(base);
        int before = engine.calls();

        assertThat(send(base, cookie, "/api/vocab/audio?text=").statusCode()).isEqualTo(400);
        assertThat(send(base, cookie, "/api/vocab/audio?text=%20%20").statusCode()).isEqualTo(400);
        // 201 个 code point：超长返回 400，不静默截断
        assertThat(send(base, cookie, audioText("a".repeat(201), "en")).statusCode()).isEqualTo(400);
        // 非法请求在合成之前就被拒绝
        assertThat(engine.calls()).isEqualTo(before);

        // 边界：正好 200 字符允许（超过才 400）
        assertThat(send(base, cookie, audioText("b".repeat(200), "en")).statusCode()).isEqualTo(200);

        assertThat(send(base, cookie, "/api/vocab/audio?form=hello&text=hello").statusCode())
                .isEqualTo(400);
        assertThat(send(base, cookie, audioText("hola amigo", "fr")).statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("019/AC-57.8：/api/vocab/config 默认返回 sentenceAudio=true")
    void configDefaultsToSentenceAudioEnabled() throws Exception {
        String base = base();
        String cookie = login(base);
        HttpResponse<String> response = sendText(base, cookie, "/api/vocab/config");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("sentenceAudio").asBoolean()).isTrue();
    }

    private String audioText(String text, String lang) {
        return "/api/vocab/audio?text="
                + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + (lang == null ? "" : "&lang=" + lang);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private HttpResponse<String> sendText(String base, String cookie, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN").GET();
        if (cookie != null) builder.header("Cookie", cookie);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
