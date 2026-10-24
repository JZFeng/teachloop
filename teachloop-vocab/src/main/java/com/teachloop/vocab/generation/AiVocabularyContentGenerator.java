package com.teachloop.vocab.generation;

import com.teachloop.shared.port.Ports;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容端点（DeepSeek）的内容生成实现（plan §4）。
 *
 * <p>配置来自 identity 的 {@link Ports.AiSettingsLookup}（全局当前 provider），
 * 只发送词形与学习语言，不发送任何用户数据；响应必须是 JSON 对象，
 * 否则按失败处理（不写半成品）。
 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.generation.engine", havingValue = "ai", matchIfMissing = true)
public class AiVocabularyContentGenerator implements VocabularyContentGenerator {

    private static final String SYSTEM_PROMPT = """
            You generate vocabulary learning content. Return ONLY a JSON object with keys:
            senses: array of {pos, zh, en}
            examples: array of {en, zh}
            analysis: string (word formation hint, short)
            family: array of related word forms (strings)
            phonetic: {uk, us}
            No markdown, no extra text.
            """;

    private static final int ERROR_BODY_LIMIT = 400;

    private final Ports.AiSettingsLookup settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AiVocabularyContentGenerator(
            Ports.AiSettingsLookup settings,
            @Value("${teachloop.vocab.generation.timeout-ms:60000}") long timeoutMs) {
        this.settings = settings;
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
    }

    @Override
    public String generate(String form, String language) {
        Ports.AiSettings ai = settings.current()
                .filter(Ports.AiSettings::configured)
                .orElseThrow(() -> new ContentGenerationException("AI settings are not configured"));
        String endpoint = stripTrailingSlash(ai.baseUrl()) + "/chat/completions";
        String body;
        try {
            body = mapper.writeValueAsString(Map.of(
                    "model", ai.model(),
                    "temperature", 0.2,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content",
                                    "Word: " + form + "\nLearning language: " + language))));
        } catch (RuntimeException ex) {
            throw new ContentGenerationException("cannot build request: " + ex.getMessage(), ex);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (ai.apiKey() != null && !ai.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + ai.apiKey().strip());
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException ex) {
            throw new ContentGenerationException("AI request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ContentGenerationException("AI request interrupted", ex);
        }
        if (response.statusCode() / 100 != 2) {
            String snippet = response.body() == null ? "" : response.body();
            if (snippet.length() > ERROR_BODY_LIMIT) {
                snippet = snippet.substring(0, ERROR_BODY_LIMIT);
            }
            throw new ContentGenerationException("AI returned HTTP " + response.statusCode() + ": " + snippet);
        }
        return contentOf(response.body());
    }

    private String contentOf(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            Optional<JsonNode> contentNode = Optional.ofNullable(root.path("choices").path(0)
                    .path("message").path("content"));
            String content = contentNode.filter(JsonNode::isString).map(JsonNode::asString)
                    .orElseThrow(() -> new ContentGenerationException("AI response has no message content"));
            JsonNode parsed = mapper.readTree(content);
            if (!parsed.isObject() || !parsed.path("senses").isArray()) {
                throw new ContentGenerationException("AI content is not the expected JSON object");
            }
            return content;
        } catch (ContentGenerationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ContentGenerationException("AI response is not valid JSON: " + ex.getMessage(), ex);
        }
    }

    private static String stripTrailingSlash(String value) {
        String base = value == null || value.isBlank() ? "https://api.deepseek.com" : value.strip();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
