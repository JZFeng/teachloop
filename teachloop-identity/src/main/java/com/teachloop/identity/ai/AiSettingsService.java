package com.teachloop.identity.ai;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 全局 AI 设置（D-16 / D-30 修订）。
 *
 * <p>v1 只有一份全局配置：管理员维护 {@code ai_providers} 列表，{@code ai_settings.active_provider_id}
 * 指向当前使用的那一条；教师 /profile 的下拉只是切换这个全局指针。
 * 旧的 {@code ai_settings} 四字段保留为兼容回退（没有 active provider 时使用）。
 */
@Service
public class AiSettingsService {

    public record View(String provider, String baseUrl, String model, String temperature,
                       boolean configured, boolean apiKeySet, String apiKeyMasked, String updatedAt,
                       boolean restartRequired) {
    }

    public record UpdateRequest(String provider, String displayName, String baseUrl, String model,
                                String apiKey, BigDecimal temperature) {

        /** 兼容旧调用（setup 只传 key）。 */
        public UpdateRequest(String provider, String baseUrl, String model, String apiKey,
                             BigDecimal temperature) {
            this(provider, null, baseUrl, model, apiKey, temperature);
        }
    }

    /** 决策 022：管理端要看到的故障转移事实（不含任何密钥）。 */
    public record FailoverState(boolean active, String fromProviderKey, String reason,
                                String since, String activeProviderId, String activeProviderKey) {
    }

    public record TestRequest(String baseUrl, String model, String apiKey, String extraHeaders) {

        /** 兼容旧调用方（不带额外头）。 */
        public TestRequest(String baseUrl, String model, String apiKey) {
            this(baseUrl, model, apiKey, null);
        }
    }

    public record TestResult(boolean ok, long latencyMs, String message, String model) {
    }

    public record ModelsResult(boolean ok, List<String> models, String message) {
    }

    private static final Logger log = LoggerFactory.getLogger(AiSettingsService.class);

    /** 探测与判分统一用产品 UA：Cloudflare 前置的网关会拦掉默认 UA（error 1010）。 */
    static final String USER_AGENT = "teachloop/1.0";

    private final AiSettingsRepository repo;
    private final AiProviderRepository providers;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AiSettingsService(AiSettingsRepository repo, AiProviderRepository providers,
                             SecretBox secretBox, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.providers = providers;
        this.secretBox = secretBox;
        this.mapper = mapper;
        this.clock = clock;
    }

    public View view() {
        AiSettingsEntity e = repo.findById(1).orElseThrow(() -> ApiException.notFound("AI 配置不存在"));
        String key = currentKey(e);
        Optional<AiProviderEntity> active = activeProvider(e);
        String provider = active.map(AiProviderEntity::getProviderKey).orElse(e.getProvider());
        String baseUrl = active.map(AiProviderEntity::getBaseUrl).orElse(e.getBaseUrl());
        String model = active.map(p -> {
            String activeModel = e.getActiveModel();
            return activeModel == null || activeModel.isBlank() ? p.getModel() : activeModel;
        }).orElse(e.getModel());
        return new View(provider, baseUrl, model,
                e.getTemperature().stripTrailingZeros().toPlainString(),
                lookup().map(Ports.AiSettings::configured).orElse(false),
                key != null && !key.isBlank(), mask(key), e.getUpdatedAt().toString(), false);
    }

    public Optional<Ports.AiSettings> lookup() {
        AiSettingsEntity e = repo.findById(1).orElse(null);
        if (e == null) {
            return Optional.empty();
        }
        Optional<AiProviderEntity> active = activeProvider(e);
        if (active.isPresent()) {
            AiProviderEntity p = active.get();
            String key = secretBox.decrypt(p.getApiKeyCipher());
            String model = e.getActiveModel() == null || e.getActiveModel().isBlank()
                    ? p.getModel() : e.getActiveModel();
            return Optional.of(new Ports.AiSettings(p.getProviderKey(), p.getBaseUrl(), model, key,
                    e.getTemperature(), true, "global", extraHeaders(p)));
        }
        String key = secretBox.decrypt(e.getApiKeyCipher());
        return Optional.of(new Ports.AiSettings(e.getProvider(), e.getBaseUrl(), e.getModel(), key,
                e.getTemperature(), isConfigured(e.getProvider(), e.getBaseUrl(), e.getModel(), key), "global"));
    }

    /** v1 只有全局模型；保留 teacherId 参数是为了不改 grading 的端口调用。 */
    public Optional<Ports.AiSettings> resolve(UUID teacherId) {
        return lookup().filter(Ports.AiSettings::configured);
    }

    /** 决策 022：当前是否处于故障转移、从哪条切来、什么时候、为什么。 */
    @Transactional(readOnly = true)
    public FailoverState failoverState() {
        AiSettingsEntity e = repo.findById(1).orElse(null);
        if (e == null) {
            return new FailoverState(false, null, null, null, null, null);
        }
        String activeKey = activeProvider(e).map(AiProviderEntity::getProviderKey).orElse(null);
        return new FailoverState(
                e.getFailoverFromProviderKey() != null && !"manual".equals(e.getFailoverReason()),
                e.getFailoverFromProviderKey(),
                e.getFailoverReason(),
                e.getLastFailoverAt() == null ? null : e.getLastFailoverAt().toString(),
                e.getActiveProviderId() == null ? null : e.getActiveProviderId().toString(),
                activeKey);
    }

    public UUID activeProviderId() {
        return repo.findById(1).map(AiSettingsEntity::getActiveProviderId).orElse(null);
    }

    public String activeModel() {
        return repo.findById(1).map(AiSettingsEntity::getActiveModel).orElse(null);
    }

    @Transactional
    public void setActiveProvider(UUID providerId, String model, UUID actorId) {
        AiSettingsEntity e = repo.findById(1).orElseThrow();
        e.setActiveProviderId(providerId);
        e.setActiveModel(model);
        e.setUpdatedAt(clock.instant());
        e.setUpdatedBy(actorId);
        repo.save(e);
    }

    @Transactional
    public void clearActiveIf(UUID providerId, UUID actorId) {
        AiSettingsEntity e = repo.findById(1).orElseThrow();
        if (providerId.equals(e.getActiveProviderId())) {
            e.setActiveProviderId(null);
            e.setActiveModel(null);
            e.setUpdatedAt(clock.instant());
            e.setUpdatedBy(actorId);
            repo.save(e);
        }
    }

    private Optional<AiProviderEntity> activeProvider(AiSettingsEntity e) {
        if (e.getActiveProviderId() == null) {
            return Optional.empty();
        }
        return providers.findById(e.getActiveProviderId());
    }

    private String currentKey(AiSettingsEntity e) {
        return activeProvider(e)
                .map(p -> secretBox.decrypt(p.getApiKeyCipher()))
                .orElseGet(() -> secretBox.decrypt(e.getApiKeyCipher()));
    }

    /**
     * 保留旧接口：`/api/admin/ai-settings` 仍可写兼容字段；新 UI 走 provider 列表。
     * 若当前有 active provider，旧字段不参与解析。
     */
    @Transactional
    public View update(UUID adminId, UpdateRequest req) {
        AiSettingsEntity e = repo.findById(1).orElseThrow();
        if (req.provider() != null && !req.provider().isBlank()) e.setProvider(req.provider().strip());
        if (req.baseUrl() != null && !req.baseUrl().isBlank()) {
            String url = req.baseUrl().strip();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw ApiException.badRequest("baseUrl 必须以 http:// 或 https:// 开头");
            }
            e.setBaseUrl(url);
        }
        if (req.model() != null && !req.model().isBlank()) e.setModel(req.model().strip());
        if (req.temperature() != null) {
            if (req.temperature().signum() < 0 || req.temperature().compareTo(BigDecimal.ONE) > 0) {
                throw ApiException.badRequest("temperature 必须在 0–1 之间");
            }
            e.setTemperature(req.temperature());
        }
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            e.setApiKeyCipher(secretBox.encrypt(req.apiKey().strip()));
        }
        e.setUpdatedAt(clock.instant());
        e.setUpdatedBy(adminId);
        repo.save(e);
        return view();
    }

    /** 用提交值测试；空 Key 也允许（本地模型）。 */
    public TestResult test(TestRequest req) {
        return testWith(req, effectiveKey(req));
    }

    /** 模型发现：GET {baseUrl}/models。空 Key 也允许，本地服务器通常不需要鉴权。 */
    public ModelsResult listModels(TestRequest req) {
        String baseUrl = effectiveBaseUrl(req);
        if (baseUrl == null || baseUrl.isBlank()) {
            return new ModelsResult(false, List.of(), "还没有填写 Base URL");
        }
        String key = effectiveKey(req);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(baseUrl.replaceAll("/+$", "") + "/models"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    // 有些网关（Cloudflare 前置）没有产品 UA 直接 403 error code: 1010
                    .header("User-Agent", USER_AGENT);
            if (key != null && !key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }
            applyExtraHeaders(builder, req.extraHeaders());
            HttpResponse<String> response = http.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ModelsResult(false, List.of(), "HTTP " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.path("data");
            List<String> models = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String id = item.path("id").isString() ? item.path("id").asString() : null;
                    if (id != null && !id.isBlank()) {
                        models.add(id);
                    }
                }
            }
            models.sort(String::compareTo);
            return new ModelsResult(true, models,
                    models.isEmpty() ? "端点返回的模型列表为空" : "已加载 " + models.size() + " 个模型");
        } catch (Exception ex) {
            return new ModelsResult(false, List.of(), "加载失败：" + ex.getClass().getSimpleName());
        }
    }

    private TestResult testWith(TestRequest req, String key) {
        String baseUrl = effectiveBaseUrl(req);
        String model = effectiveModel(req);
        if (baseUrl == null || baseUrl.isBlank()) {
            return new TestResult(false, 0, "还没有填写 Base URL", model);
        }
        if (model == null || model.isBlank()) {
            return new TestResult(false, 0, "还没有填写模型名", model);
        }
        long started = System.nanoTime();
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "temperature", 0.2,
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 8));
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT);
            if (key != null && !key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }
            applyExtraHeaders(builder, req.extraHeaders());
            HttpResponse<String> response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new TestResult(true, ms, "连接成功（HTTP 200）", model);
            }
            String hint = response.statusCode() == 401 || response.statusCode() == 403
                    ? "认证失败，检查 API Key（本地模型通常不需要 Key）" : "HTTP " + response.statusCode();
            return new TestResult(false, ms, hint, model);
        } catch (Exception ex) {
            long ms = (System.nanoTime() - started) / 1_000_000;
            return new TestResult(false, ms, "连接失败：" + ex.getClass().getSimpleName(), model);
        }
    }

    private String effectiveBaseUrl(TestRequest req) {
        if (req.baseUrl() != null && !req.baseUrl().isBlank()) {
            return AiProviderService.normalizeBaseUrl(req.baseUrl());
        }
        return repo.findById(1).map(e -> AiProviderService.normalizeBaseUrl(
                activeProvider(e).map(AiProviderEntity::getBaseUrl).orElse(e.getBaseUrl()))).orElse(null);
    }

    private String effectiveModel(TestRequest req) {
        if (req.model() != null && !req.model().isBlank()) {
            return req.model().strip();
        }
        return repo.findById(1).map(e -> activeProvider(e)
                .map(p -> e.getActiveModel() == null || e.getActiveModel().isBlank()
                        ? p.getModel() : e.getActiveModel())
                .orElse(e.getModel())).orElse(null);
    }

    private String effectiveKey(TestRequest req) {
        if (req.apiKey() != null) {
            return req.apiKey().strip();
        }
        // provider 表单会同时带 baseUrl/model；此时空 Key 就是空 Key，不能回退到当前 provider 的 Key。
        if (req.baseUrl() != null && !req.baseUrl().isBlank()) {
            return "";
        }
        return repo.findById(1).map(this::currentKey).orElse("");
    }

    /**
     * 旧字段的可用性：baseUrl 与 model 必须非空；本地 provider 允许没有 Key，云端 provider 仍要 Key。
     * active provider 走自己的路径，不走这里。
     */
    private static boolean isConfigured(String provider, String baseUrl, String model, String key) {
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
            return false;
        }
        if (key != null && !key.isBlank()) {
            return true;
        }
        return isLocalProvider(provider) || isLocalHost(baseUrl);
    }

    private static boolean isLocalProvider(String provider) {
        if (provider == null) {
            return false;
        }
        String p = provider.toLowerCase();
        return p.contains("ollama") || p.contains("lmstudio") || p.contains("lm-studio")
                || p.contains("vllm") || p.contains("llamacpp") || p.contains("llama.cpp")
                || p.contains("localai") || p.equals("local");
    }

    private static boolean isLocalHost(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            if (host == null) {
                return false;
            }
            String h = host.toLowerCase();
            if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("0.0.0.0")) {
                return true;
            }
            return h.startsWith("10.") || h.startsWith("192.168.")
                    || h.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.length() <= 6) {
            return "****";
        }
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }

    /**
     * 决策 024：provider 级额外请求头。值为 {@code $uuid} 时每次解析生成一个新 UUID，
     * 用于只要求「每次请求不同」的网关路由头（如 opencode go 的 {@code x-opencode-session}）。
     *
     * <p>配置写坏不拦住判分：解析失败只记一条 warn 并返回空表。
     */
    private Map<String, String> extraHeaders(AiProviderEntity p) {
        String raw = p.getExtraHeadersJson();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = mapper.readValue(raw, new TypeReference<Map<String, String>>() { });
            Map<String, String> resolved = new LinkedHashMap<>();
            parsed.forEach((name, value) -> {
                if (name == null || name.isBlank()) {
                    return;
                }
                String text = value == null ? "" : value;
                resolved.put(name.strip(), "$uuid".equals(text.strip()) ? UUID.randomUUID().toString() : text);
            });
            return resolved;
        } catch (Exception ex) {
            log.warn("provider {} 的 extraHeaders 不是合法 JSON，已忽略：{}", p.getProviderKey(), ex.getMessage());
            return Map.of();
        }
    }

    /** 决策 024：把 provider 级额外头应用到探测请求上（值为 $uuid 时生成 UUID）。 */
    private void applyExtraHeaders(HttpRequest.Builder builder, String rawJson) {
        Map<String, String> headers = parseHeaders(rawJson);
        headers.forEach((name, value) -> {
            if (name == null || name.isBlank()) {
                return;
            }
            String header = name.strip();
            if ("authorization".equalsIgnoreCase(header) || "content-type".equalsIgnoreCase(header)
                    || "accept".equalsIgnoreCase(header) || "user-agent".equalsIgnoreCase(header)) {
                return;
            }
            try {
                builder.header(header, value);
            } catch (IllegalArgumentException restricted) {
                // Host/Content-Length 等受限头跳过
            }
        });
    }

    /** 解析 {\"name\": \"value\"} 形式的额外头；值为 $uuid 时生成新 UUID。 */
    private Map<String, String> parseHeaders(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> parsed = mapper.readValue(rawJson.strip(), Map.class);
            Map<String, String> resolved = new LinkedHashMap<>();
            parsed.forEach((name, value) -> {
                if (name == null || value == null) {
                    return;
                }
                String text = String.valueOf(value);
                resolved.put(String.valueOf(name), "$uuid".equals(text.strip()) ? UUID.randomUUID().toString() : text);
            });
            return resolved;
        } catch (RuntimeException ex) {
            log.warn("extraHeaders 不是合法 JSON，已忽略：{}", ex.getMessage());
            return Map.of();
        }
    }
}
