package com.teachloop.vocab.generation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 词内容 prompt 与请求参数的唯一来源（AC-60.12 / T439）。
 *
 * <p>资源 {@code /vocab/content-prompt-v2.3.json} 同时被 Python 批量工具
 * （{@code scripts/vocab/generate_content.py}）读取，禁止两边各维护一份。
 * 升版必须同时改文件名、本类的 {@link #DEFAULT_RESOURCE} 与 {@code promptVersion} 字段。
 */
@Component
public class ContentPromptContract {

    static final String DEFAULT_RESOURCE = "/vocab/content-prompt-v2.3.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode root;
    private final String promptSha256;

    public ContentPromptContract() {
        this(DEFAULT_RESOURCE);
    }

    ContentPromptContract(String resource) {
        this.root = load(resource);
        String system = systemPrompt();
        if (system.isBlank()) {
            throw new IllegalStateException("词内容 prompt 契约缺少 system 字段：" + resource);
        }
        this.promptSha256 = sha256(system);
    }

    private JsonNode load(String resource) {
        try (InputStream in = ContentPromptContract.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少词内容 prompt 契约：" + resource);
            }
            return mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("读取词内容 prompt 契约失败：" + resource, ex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("解析词内容 prompt 契约失败：" + resource, ex);
        }
    }

    /** 例如 {@code vocab-content-v2.3}；写进 delta/包的 prompt_version，升版即失效旧内容。 */
    public String promptVersion() {
        return text(root, "promptVersion");
    }

    /** system prompt 的 sha256 前 16 位，写进 manifest，便于确认内容由哪版 prompt 生成。 */
    public String promptSha256() {
        return promptSha256;
    }

    public String systemPrompt() {
        return text(root, "system");
    }

    public List<String> languages() {
        JsonNode node = root.path("languages");
        List<String> languages = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> {
                if (item.isString() && !item.asString().isBlank()) {
                    languages.add(item.asString());
                }
            });
        }
        return List.copyOf(languages);
    }

    public int exampleMin() {
        return root.path("examples").path("minPerWord").asInt(1);
    }

    public int exampleMax() {
        return root.path("examples").path("maxPerWord").asInt(6);
    }

    /** 与 Python 批量工具同一模板，保证批量与实时措辞一致。 */
    public String userPrompt(String word, String language, int exampleCount) {
        String others = String.join(", ", languages().stream()
                .filter(candidate -> !candidate.equals(language))
                .toList());
        return text(root, "userPrompt")
                .replace("{word}", word)
                .replace("{language}", language)
                .replace("{otherLanguages}", others)
                .replace("{exampleCount}", Integer.toString(exampleCount));
    }

    public RequestDefaults request() {
        JsonNode node = root.path("request");
        return new RequestDefaults(
                node.path("temperature").asDouble(0.2),
                node.path("temperatureRetry").asDouble(0.1),
                node.path("maxTokens").asInt(6000),
                node.path("maxTokensLocal").asInt(4000),
                Duration.ofSeconds(Math.max(1, node.path("timeoutSeconds").asInt(20))),
                text(node, "userAgent"),
                node.path("responseFormat"));
    }

    /** 契约里某个 provider 的默认 baseUrl（部署配置缺失时兜底 / 联调脚本取值）。 */
    public String providerBaseUrl(String name) {
        return text(root.path("providers").path(name), "baseUrl");
    }

    /** 契约里某个 provider 的默认模型名。 */
    public String providerModel(String name) {
        return text(root.path("providers").path(name), "model");
    }

    /** 按 baseUrl 前缀匹配 provider 档案；匹配不到返回空（此时不发任何厂商私有字段）。 */
    public Optional<ProviderProfile> providerFor(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        String target = baseUrl.strip();
        JsonNode providers = root.path("providers");
        if (!providers.isObject()) {
            return Optional.empty();
        }
        for (String name : providers.propertyNames()) {
            JsonNode provider = providers.path(name);
            String prefix = text(provider, "baseUrl");
            if (!prefix.isBlank() && target.startsWith(prefix)) {
                return Optional.of(new ProviderProfile(
                        name, prefix, text(provider, "sessionHeader"), text(provider, "disableThinking"),
                        provider.path("timeoutSeconds").asInt(0)));
            }
        }
        return Optional.empty();
    }

    public record RequestDefaults(double temperature, double temperatureRetry, int maxTokens,
                                  int maxTokensLocal, Duration timeout, String userAgent,
                                  JsonNode responseFormat) {
    }

    /**
     * @param sessionHeader   需要时每请求带随机 UUID 的头名（OpenCode Go 必需），没有则为空
     * @param disableThinking {@code thinking} / {@code chat_template_kwargs} / 空（不动）
     */
    public record ProviderProfile(String name, String baseUrl, String sessionHeader, String disableThinking,
                                  int timeoutSeconds) {

        /** 0 = 用 {@link RequestDefaults#timeout()}；本地单槽模型要单独放宽。 */
        public boolean hasOwnTimeout() {
            return timeoutSeconds > 0;
        }

        public boolean sendsSessionHeader() {
            return sessionHeader != null && !sessionHeader.isBlank();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.asString() : "";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** 仅供测试/诊断：契约里的 provider 名列表。 */
    List<String> providerNames() {
        List<String> names = new ArrayList<>();
        JsonNode providers = root.path("providers");
        if (providers.isObject()) {
            providers.propertyNames().forEach(names::add);
        }
        return List.copyOf(names);
    }
}
