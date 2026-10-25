package com.teachloop.vocab.generation;

import com.teachloop.shared.port.Ports;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI 兼容端点的内容生成实现（AC-60.12 / T439 / plan §4）。
 *
 * <p>prompt 与请求参数全部来自 {@link ContentPromptContract}（与 Python 批量工具同一份 JSON），
 * 本类不再自带 prompt。四个坑由代码而不是文档保证：
 * <ul>
 *   <li>thinking 必须显式关闭（默认先吐 9k–24k reasoning tokens，把 max_tokens 吃光导致正文截断）；</li>
 *   <li>不能留库默认 User-Agent（Python urllib 默认 UA 会被 Cloudflare 拦成 403 error 1010）；</li>
 *   <li>OpenCode Go 需要每请求随机的 {@code x-opencode-session}（缺失返回 400 MissingSessionID）；</li>
 *   <li>截断不算成功：{@code finish_reason=length}、输出顶满预算、reasoning tokens 超限、空正文一律判失败。</li>
 * </ul>
 *
 * <p>失败重试一次（温度降到契约里的 {@code temperatureRetry}），仍失败抛
 * {@link ContentGenerationException}，由 worker 置 FAILED；绝不会把半成品写进内容存储。
 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.generation.engine", havingValue = "ai", matchIfMissing = true)
public class AiVocabularyContentGenerator implements VocabularyContentGenerator {

    private static final Logger log = LoggerFactory.getLogger(AiVocabularyContentGenerator.class);
    private static final int ERROR_BODY_LIMIT = 400;
    private static final int MAX_REASONING_TOKENS = 200;
    private static final int ATTEMPTS = 2;

    private final Ports.AiSettingsLookup settings;
    private final ContentPromptContract contract;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeoutOverride;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AiVocabularyContentGenerator(
            Ports.AiSettingsLookup settings,
            ContentPromptContract contract,
            @Value("${teachloop.vocab.generation.timeout-ms:0}") long timeoutMs) {
        this.settings = settings;
        this.contract = contract;
        // 0 = 用契约里的 timeoutSeconds；非 0 时才覆盖（部署级调参用）
        this.timeoutOverride = timeoutMs > 0 ? Duration.ofMillis(timeoutMs) : null;
    }

    @Override
    public String generate(String form, String language) {
        Ports.AiSettings ai = settings.current()
                .filter(Ports.AiSettings::configured)
                .orElseThrow(() -> new ContentGenerationException("AI settings are not configured"));
        ContentPromptContract.RequestDefaults defaults = contract.request();
        Optional<ContentPromptContract.ProviderProfile> profile = contract.providerFor(ai.baseUrl());
        int maxTokens = profile.map(ContentPromptContract.ProviderProfile::name)
                .filter("local"::equals)
                .map(name -> defaults.maxTokensLocal())
                .orElse(defaults.maxTokens());
        Duration timeout = timeoutOverride != null ? timeoutOverride : defaults.timeout();
        String endpoint = stripTrailingSlash(ai.baseUrl()) + "/chat/completions";

        List<String> problems = new ArrayList<>();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            double temperature = attempt == 0 ? defaults.temperature() : defaults.temperatureRetry();
            String body = buildBody(ai.model(), form, language, temperature, maxTokens,
                    profile.orElse(null), defaults);
            String envelope = post(endpoint, ai.apiKey(), body, defaults, profile.orElse(null), timeout);
            Envelope parsed = parseEnvelope(envelope, maxTokens);
            if (!parsed.problems().isEmpty()) {
                problems = parsed.problems();
                log.debug("内容生成第 {} 次失败 word={}：{}", attempt + 1, form, problems);
                continue;
            }
            ObjectNode content;
            try {
                JsonNode parsedContent = mapper.readTree(parsed.content());
                if (!parsedContent.isObject()) {
                    problems = List.of("content is not a JSON object");
                    continue;
                }
                content = (ObjectNode) parsedContent;
            } catch (RuntimeException ex) {
                problems = List.of("content is not valid JSON: " + ex.getMessage());
                continue;
            }
            problems = ContentSchemaV2.validateAndClean(content, form, language,
                    contract.exampleMin(), contract.exampleMax());
            if (problems.isEmpty()) {
                log.debug("内容生成成功 word={} attempt={} prompt={}/{}",
                        form, attempt + 1, contract.promptVersion(), contract.promptSha256());
                return mapper.writeValueAsString(content);
            }
            log.debug("内容不合格 word={} attempt={}：{}", form, attempt + 1, problems);
        }
        throw new ContentGenerationException(
                "AI content rejected after " + ATTEMPTS + " attempts: " + String.join("; ", problems));
    }

    private String buildBody(String model, String form, String language, double temperature,
                             int maxTokens, ContentPromptContract.ProviderProfile profile,
                             ContentPromptContract.RequestDefaults defaults) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.set("response_format", defaults.responseFormat());
        body.put("max_tokens", maxTokens);
        if (profile != null) {
            String style = profile.disableThinking() == null ? "" : profile.disableThinking();
            switch (style) {
                case "thinking" -> body.putObject("thinking").put("type", "disabled");
                case "chat_template_kwargs" ->
                        body.putObject("chat_template_kwargs").put("enable_thinking", false);
                default -> {
                    // 未知厂商：不发私有字段，避免 400
                }
            }
        }
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", contract.systemPrompt());
        messages.addObject().put("role", "user").put("content",
                contract.userPrompt(form, language, contract.exampleMax()));
        return mapper.writeValueAsString(body);
    }

    private String post(String endpoint, String apiKey, String body,
                        ContentPromptContract.RequestDefaults defaults,
                        ContentPromptContract.ProviderProfile profile, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", defaults.userAgent())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.strip());
        }
        if (profile != null && profile.sendsSessionHeader()) {
            builder.header(profile.sessionHeader(), UUID.randomUUID().toString());
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
        return response.body();
    }

    /** 只判「这次响应能不能用」，不解析内容结构。 */
    private Envelope parseEnvelope(String responseBody, int maxTokens) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody == null ? "" : responseBody);
        } catch (RuntimeException ex) {
            return new Envelope("", List.of("response envelope is not JSON"));
        }
        JsonNode choice = root.path("choices").path(0);
        String finishReason = text(choice, "finish_reason");
        String content = text(choice.path("message"), "content");
        JsonNode usage = root.path("usage");
        int completionTokens = asInt(usage, "completion_tokens");
        int reasoningTokens = asInt(usage.path("completion_tokens_details"), "reasoning_tokens");

        List<String> problems = new ArrayList<>();
        if ("length".equals(finishReason)) {
            problems.add("finish_reason=length (max_tokens=" + maxTokens + " 不够)");
        } else if (!finishReason.isBlank() && !"stop".equals(finishReason)) {
            problems.add("finish_reason=" + finishReason);
        }
        if (content.isBlank()) {
            problems.add("empty content");
        }
        if (completionTokens >= maxTokens) {
            problems.add("completion_tokens hit the max_tokens budget");
        }
        if (reasoningTokens > MAX_REASONING_TOKENS) {
            problems.add("thinking not disabled (reasoning_tokens=" + reasoningTokens + ")");
        }
        return new Envelope(content, problems);
    }

    private record Envelope(String content, List<String> problems) {
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.asString() : "";
    }

    private static int asInt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.asInt() : 0;
    }

    private static String stripTrailingSlash(String value) {
        String base = value == null || value.isBlank() ? "https://api.deepseek.com" : value.strip();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 日志里只放模型名，不放 Key。 */
    String describeModel(Ports.AiSettings ai) {
        return (ai.provider() == null ? "" : ai.provider().toLowerCase(Locale.ROOT) + "/")
                + (ai.model() == null ? "" : ai.model());
    }
}
