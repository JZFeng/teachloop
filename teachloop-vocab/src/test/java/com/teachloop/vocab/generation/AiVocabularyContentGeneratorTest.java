package com.teachloop.vocab.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AC-60.12 / T439：请求参数与响应判定。
 *
 * <p>用本地 {@link HttpServer} 当 AI 端点，只验证「发出去的参数对不对、坏响应是否被判失败」，
 * 不访问真实模型（真实调用的 p50 在 QA 手工验收里测）。
 */
class AiVocabularyContentGeneratorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ContentPromptContract contract = new ContentPromptContract("/vocab/content-prompt-test.json");
    private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentLinkedQueue<String> queued = new ConcurrentLinkedQueue<>();

    private HttpServer server;
    private volatile int errorStatus;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recorded.add(new Recorded(body, exchange.getRequestHeaders().getFirst("User-Agent"),
                    exchange.getRequestHeaders().getFirst("x-opencode-session"),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            int status = errorStatus;
            String payload = status >= 400
                    ? "{\"error\":{\"message\":\"boom\"}}"
                    : queued.isEmpty() ? envelope(mapper.writeValueAsString(sample("apple", 6)), "stop", 1200, 0)
                    : queued.poll();
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status >= 400 ? status : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    private AiVocabularyContentGenerator generator(String baseUrl) {
        return new AiVocabularyContentGenerator(settings(baseUrl), contract, 0);
    }

    /** 单独的 settings 替身：只有 current() 与 resolve() 是抽象的。 */
    private com.teachloop.shared.port.Ports.AiSettingsLookup settings(String baseUrl) {
        return new com.teachloop.shared.port.Ports.AiSettingsLookup() {
            @Override
            public Optional<com.teachloop.shared.port.Ports.AiSettings> current() {
                return Optional.of(new com.teachloop.shared.port.Ports.AiSettings(
                        "opencode", baseUrl, "deepseek-v4.1-flash", "test-key",
                        new BigDecimal("0.2"), true));
            }

            @Override
            public Optional<com.teachloop.shared.port.Ports.AiSettings> resolve(UUID teacherId) {
                return current();
            }
        };
    }

    @Test
    void sendsContractParamsHeadersAndReturnsCleanContent() {
        String content = generator(baseUrl()).generate("apple", "en");

        JsonNode parsed = mapper.readTree(content);
        assertThat(parsed.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(parsed.path("examples")).hasSize(6);
        assertThat(parsed.path("examples").get(0).path("popularity").asInt()).isEqualTo(1);

        Recorded request = recorded.get(0);
        JsonNode body = mapper.readTree(request.body());
        assertThat(body.path("model").asString()).isEqualTo("deepseek-v4.1-flash");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(6000);
        assertThat(body.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(body.path("thinking").path("type").asString()).isEqualTo("disabled");
        assertThat(body.path("messages").get(0).path("content").asString())
                .isEqualTo(contract.systemPrompt());
        assertThat(body.path("messages").get(1).path("content").asString())
                .contains("Word: apple")
                .contains("Target language: en");

        assertThat(request.userAgent()).isEqualTo(contract.request().userAgent());
        assertThat(UUID.fromString(request.sessionHeader())).isNotNull();
        assertThat(request.authorization()).isEqualTo("Bearer test-key");
    }

    @Test
    void retriesOnceWithLowerTemperatureWhenTruncated() {
        queued.add(envelope("", "length", 6000, 0));
        queued.add(envelope(mapper.writeValueAsString(sample("apple", 6)), "stop", 1200, 0));

        String content = generator(baseUrl()).generate("apple", "en");

        assertThat(mapper.readTree(content).path("examples")).hasSize(6);
        assertThat(recorded).hasSize(2);
        assertThat(mapper.readTree(recorded.get(0).body()).path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(mapper.readTree(recorded.get(1).body()).path("temperature").asDouble()).isEqualTo(0.1);
    }

    @Test
    void truncationTwiceIsAFailureAndNeverReturnsContent() {
        queued.add(envelope("", "length", 6000, 0));
        queued.add(envelope("", "length", 6000, 0));

        assertThatThrownBy(() -> generator(baseUrl()).generate("apple", "en"))
                .isInstanceOf(ContentGenerationException.class)
                .hasMessageContaining("finish_reason=length");
        assertThat(recorded).hasSize(2);
    }

    @Test
    void reasoningTokensMeanThinkingWasNotDisabled() {
        queued.add(envelope("", "stop", 4000, 3600));
        queued.add(envelope("", "stop", 4000, 3600));

        assertThatThrownBy(() -> generator(baseUrl()).generate("apple", "en"))
                .isInstanceOf(ContentGenerationException.class)
                .hasMessageContaining("thinking not disabled")
                // 命中契约档案时不该把责任推给「没档案」
                .hasMessageNotContaining("不在 prompt 契约的 providers 档案里");
    }

    /** 2026-10-29 prod 故障：provider 不在契约档案里时，失败信息要直接点出可修的配置点。 */
    @Test
    void failureNamesTheUnknownProviderSoTheContractCanBeFixed() {
        queued.add(envelope("", "stop", 4000, 3600));
        queued.add(envelope("", "stop", 4000, 3600));

        assertThatThrownBy(() -> generator("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .generate("apple", "en"))
                .isInstanceOf(ContentGenerationException.class)
                .hasMessageContaining("thinking not disabled")
                .hasMessageContaining("不在 prompt 契约的 providers 档案里")
                .hasMessageContaining("disableThinking");
    }

    @Test
    void invalidContentIsRejectedAfterOneRetry() {
        ObjectNode broken = sample("apple", 6);
        ((ObjectNode) broken.path("meanings")).put("en", "");
        String payload = envelope(mapper.writeValueAsString(broken), "stop", 1200, 0);
        queued.add(payload);
        queued.add(payload);

        assertThatThrownBy(() -> generator(baseUrl()).generate("apple", "en"))
                .isInstanceOf(ContentGenerationException.class)
                .hasMessageContaining("meanings.en empty");
        assertThat(recorded).hasSize(2);
    }

    @Test
    void httpErrorSurfacesWithStatusAndBody() {
        errorStatus = 503;

        assertThatThrownBy(() -> generator(baseUrl()).generate("apple", "en"))
                .isInstanceOf(ContentGenerationException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void missingSettingsNeverFallsBackToHardcodedProvider() {
        com.teachloop.shared.port.Ports.AiSettingsLookup empty =
                new com.teachloop.shared.port.Ports.AiSettingsLookup() {
                    @Override
                    public Optional<com.teachloop.shared.port.Ports.AiSettings> current() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<com.teachloop.shared.port.Ports.AiSettings> resolve(UUID teacherId) {
                        return Optional.empty();
                    }
                };

        assertThatThrownBy(() -> new AiVocabularyContentGenerator(empty, contract, 0)
                .generate("apple", "en"))
                .isInstanceOf(ContentGenerationException.class)
                .hasMessageContaining("AI settings are not configured");
        assertThat(recorded).isEmpty();
    }

    @Test
    void blankBaseUrlIsRejectedInsteadOfDefaultingToAVendor() {
        com.teachloop.shared.port.Ports.AiSettingsLookup blank =
                new com.teachloop.shared.port.Ports.AiSettingsLookup() {
                    @Override
                    public Optional<com.teachloop.shared.port.Ports.AiSettings> current() {
                        return Optional.of(new com.teachloop.shared.port.Ports.AiSettings(
                                "opencode", "   ", "deepseek-v4.1-flash", "test-key",
                                new BigDecimal("0.2"), true));
                    }

                    @Override
                    public Optional<com.teachloop.shared.port.Ports.AiSettings> resolve(UUID teacherId) {
                        return current();
                    }
                };

        assertThatThrownBy(() -> new AiVocabularyContentGenerator(blank, contract, 0)
                .generate("apple", "en"))
                .isInstanceOf(ContentGenerationException.class)
                .hasMessageContaining("baseUrl");
        assertThat(recorded).isEmpty();
    }

    @Test
    void unknownProviderSendsNoVendorSpecificFields() {
        generator("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").generate("apple", "en");

        JsonNode body = mapper.readTree(recorded.get(0).body());
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("chat_template_kwargs")).isFalse();
        assertThat(recorded.get(0).sessionHeader()).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private record Recorded(String body, String userAgent, String sessionHeader, String authorization) {
    }

    private String envelope(String content, String finishReason, int completionTokens, int reasoningTokens) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("finish_reason", finishReason);
        choice.putObject("message").put("content", content);
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 900);
        usage.put("completion_tokens", completionTokens);
        if (reasoningTokens > 0) {
            usage.putObject("completion_tokens_details").put("reasoning_tokens", reasoningTokens);
        }
        return mapper.writeValueAsString(root);
    }

    private ObjectNode sample(String word, int examples) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 2);
        root.put("word", word);
        root.put("language", "en");
        ObjectNode basic = root.putObject("basicInfo");
        ObjectNode pronunciation = basic.putObject("pronunciation");
        pronunciation.put("uk", "/ˈæp.əl/");
        pronunciation.put("us", "/ˈæp.əl/");
        basic.putArray("partOfSpeech").add("noun");
        root.putObject("meanings").put("zh", "苹果").put("en", "a round fruit");
        ObjectNode core = root.putObject("coreSemantics");
        core.put("core", "树上结的圆形果实");
        core.put("notThis", "不是泛指水果，而是特指苹果");
        core.put("mentalImage", "一个红苹果放在桌上。");
        ArrayNode ex = root.putArray("examples");
        for (int index = 1; index <= examples; index++) {
            ObjectNode example = ex.addObject();
            example.put("text", "Sentence " + index + " uses " + word + ".");
            example.putObject("translations").put("zh", "例句 " + index);
            example.put("partOfSpeech", "noun");
            example.put("usagePattern", "use " + word);
            example.put("popularity", index);
        }
        ObjectNode formation = root.putObject("wordFormation");
        formation.put("analysis", "From Old English.");
        formation.putArray("parts").addObject()
                .put("text", word).put("type", "root").put("meaning", "fruit");
        formation.put("confidence", "high");
        ObjectNode family = root.putObject("wordFamily");
        ObjectNode item = family.putArray("items").addObject();
        item.put("word", word + "s");
        item.putArray("partOfSpeech").add("noun");
        item.putObject("meanings").put("zh", "复数").put("en", "plural");
        return root;
    }
}
