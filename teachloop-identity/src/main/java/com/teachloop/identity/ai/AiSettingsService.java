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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 管理员 AI 配置：保存（Key 加密）、读取（掩码）、连通性测试。 */
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

    public record TestRequest(String baseUrl, String model, String apiKey) {
    }

    public record TestResult(boolean ok, long latencyMs, String message, String model) {
    }

    /** 个人 AI 设置页需要的字段；永远不回完整 Key。 */
    public record UserView(String provider, String displayName, String baseUrl, String model, String temperature,
                           boolean configured, boolean apiKeySet, String apiKeyMasked, String source,
                           boolean globalConfigured, String updatedAt, boolean restartRequired) {
    }

    public record ModelsResult(boolean ok, List<String> models, String message) {
    }

    private final AiSettingsRepository repo;
    private final UserAiSettingsRepository userRepo;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AiSettingsService(AiSettingsRepository repo, UserAiSettingsRepository userRepo,
                             SecretBox secretBox, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.secretBox = secretBox;
        this.mapper = mapper;
        this.clock = clock;
    }

    public View view() {
        AiSettingsEntity e = repo.findById(1).orElseThrow(() -> ApiException.notFound("AI 配置不存在"));
        String key = secretBox.decrypt(e.getApiKeyCipher());
        return new View(e.getProvider(), e.getBaseUrl(), e.getModel(),
                e.getTemperature().stripTrailingZeros().toPlainString(),
                isConfigured(e.getProvider(), e.getBaseUrl(), e.getModel(), key),
                key != null && !key.isBlank(),
                mask(key), e.getUpdatedAt().toString(), false);
    }

    public Optional<Ports.AiSettings> lookup() {
        return repo.findById(1).map(e -> {
            String key = secretBox.decrypt(e.getApiKeyCipher());
            return new Ports.AiSettings(e.getProvider(), e.getBaseUrl(), e.getModel(), key,
                    e.getTemperature(),
                    isConfigured(e.getProvider(), e.getBaseUrl(), e.getModel(), key), "global");
        });
    }

    /**
     * D-16：教师个人 Key 优先，缺失回退管理员全局 Key。
     * 未配置时返回 empty，由 grading 标 NO_KEY，绝不生成假分。
     */
    public Optional<Ports.AiSettings> resolve(UUID teacherId) {
        if (teacherId != null) {
            Optional<Ports.AiSettings> own = userRepo.findById(teacherId).map(e -> {
                String key = secretBox.decrypt(e.getApiKeyCipher());
                return new Ports.AiSettings(e.getProvider(), e.getBaseUrl(), e.getModel(), key,
                        e.getTemperature(),
                        isConfigured(e.getProvider(), e.getBaseUrl(), e.getModel(), key), "user");
            });
            if (own.isPresent() && own.get().configured()) {
                return own;
            }
        }
        return lookup().filter(Ports.AiSettings::configured);
    }

    public UserView userView(UUID userId) {
        Optional<UserAiSettingsEntity> own = userRepo.findById(userId);
        Optional<AiSettingsEntity> global = repo.findById(1);
        String key = own.map(e -> secretBox.decrypt(e.getApiKeyCipher())).orElse(null);
        boolean ownKeySet = key != null && !key.isBlank();
        String globalKey = global.map(e -> secretBox.decrypt(e.getApiKeyCipher())).orElse(null);
        boolean globalKeySet = globalKey != null && !globalKey.isBlank();
        String provider = own.map(UserAiSettingsEntity::getProvider)
                .orElseGet(() -> global.map(AiSettingsEntity::getProvider).orElse("openai-compatible"));
        String displayName = own.map(UserAiSettingsEntity::getDisplayName)
                .orElseGet(() -> global.map(AiSettingsEntity::getProvider).orElse(provider));
        if (displayName == null || displayName.isBlank()) {
            displayName = provider;
        }
        String baseUrl = own.map(UserAiSettingsEntity::getBaseUrl)
                .orElseGet(() -> global.map(AiSettingsEntity::getBaseUrl).orElse(""));
        String model = own.map(UserAiSettingsEntity::getModel)
                .orElseGet(() -> global.map(AiSettingsEntity::getModel).orElse(""));
        BigDecimal temperature = own.map(UserAiSettingsEntity::getTemperature)
                .orElseGet(() -> global.map(AiSettingsEntity::getTemperature).orElse(new BigDecimal("0.20")));
        boolean ownConfigured = own.map(e -> isConfigured(
                e.getProvider(), e.getBaseUrl(), e.getModel(), key)).orElse(false);
        boolean globalConfigured = global.map(e -> isConfigured(
                e.getProvider(), e.getBaseUrl(), e.getModel(), globalKey)).orElse(false);
        String source = ownConfigured ? "user" : (globalConfigured ? "global" : "none");
        String updatedAt = own.map(e -> e.getUpdatedAt().toString())
                .orElseGet(() -> global.map(e -> e.getUpdatedAt().toString()).orElse(null));
        return new UserView(provider, displayName, baseUrl, model, temperature.stripTrailingZeros().toPlainString(),
                ownConfigured || globalConfigured, ownKeySet,
                ownKeySet ? mask(key) : null, source, globalConfigured, updatedAt, false);
    }

    @Transactional
    public UserView updateUser(UUID userId, UUID actorId, UpdateRequest req) {
        Optional<AiSettingsEntity> global = repo.findById(1);
        UserAiSettingsEntity e = userRepo.findById(userId).orElseGet(() -> new UserAiSettingsEntity(
                userId,
                req.provider() == null || req.provider().isBlank()
                        ? global.map(AiSettingsEntity::getProvider).orElse("openai-compatible")
                        : req.provider().strip(),
                req.displayName() == null || req.displayName().isBlank() ? null : req.displayName().strip(),
                global.map(AiSettingsEntity::getBaseUrl).orElse(""),
                global.map(AiSettingsEntity::getModel).orElse(""),
                null,
                global.map(AiSettingsEntity::getTemperature).orElse(new BigDecimal("0.20")),
                clock.instant(), actorId));
        if (req.provider() != null && !req.provider().isBlank()) e.setProvider(req.provider().strip());
        if (req.displayName() != null) e.setDisplayName(req.displayName().strip());
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
        e.setUpdatedBy(actorId);
        userRepo.save(e);
        return userView(userId);
    }

    public TestResult testUser(UUID userId, TestRequest req) {
        return testWith(req, testKey(userId, req));
    }

    /** 空 Key 是合法状态：本地模型通常不校验 Authorization。 */
    private String testKey(UUID userId, TestRequest req) {
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            return req.apiKey().strip();
        }
        String own = userId == null ? null : storedUserKey(userId);
        if (own != null && !own.isBlank()) {
            return own;
        }
        String global = storedGlobalKey();
        return global == null ? "" : global;
    }

    private String storedUserKey(UUID userId) {
        return userRepo.findById(userId).map(e -> secretBox.decrypt(e.getApiKeyCipher())).orElse(null);
    }

    private String storedGlobalKey() {
        return repo.findById(1).map(e -> secretBox.decrypt(e.getApiKeyCipher())).orElse(null);
    }

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
        return new View(e.getProvider(), e.getBaseUrl(), e.getModel(),
                e.getTemperature().stripTrailingZeros().toPlainString(),
                isConfigured(e.getProvider(), e.getBaseUrl(), e.getModel(),
                        secretBox.decrypt(e.getApiKeyCipher())),
                req.apiKey() != null && !req.apiKey().isBlank(), mask(req.apiKey()),
                e.getUpdatedAt().toString(), false);
    }

    /** 用提交值或已保存值测试；异常也转成 ok=false，不把 Key 写日志。 */
    public TestResult test(TestRequest req) {
        return testWith(req, testKey(null, req));
    }

    private TestResult testWith(TestRequest req, String key) {
        AiSettingsEntity e = repo.findById(1).orElseThrow();
        String baseUrl = req.baseUrl() == null || req.baseUrl().isBlank() ? e.getBaseUrl() : req.baseUrl().strip();
        String model = req.model() == null || req.model().isBlank() ? e.getModel() : req.model().strip();
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
                    .header("Content-Type", "application/json");
            if (key != null && !key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }
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

    /** 模型发现：GET {baseUrl}/models。空 Key 也允许，本地服务器通常不需要鉴权。 */
    public ModelsResult listModels(UUID userId, TestRequest req) {
        String key = testKey(userId, req);
        AiSettingsEntity e = repo.findById(1).orElseThrow();
        String baseUrl = req.baseUrl() == null || req.baseUrl().isBlank() ? e.getBaseUrl() : req.baseUrl().strip();
        if (baseUrl == null || baseUrl.isBlank()) {
            return new ModelsResult(false, List.of(), "还没有填写 Base URL");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(baseUrl.replaceAll("/+$", "") + "/models"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json");
            if (key != null && !key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> response = http.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ModelsResult(false, List.of(), "HTTP " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.path("data");
            List<String> models = new java.util.ArrayList<>();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String id = item.path("id").isString() ? item.path("id").asString() : null;
                    if (id != null && !id.isBlank()) {
                        models.add(id);
                    }
                }
            }
            models.sort(String::compareTo);
            return new ModelsResult(true, models, models.isEmpty() ? "端点返回的模型列表为空" : "已加载 " + models.size() + " 个模型");
        } catch (Exception ex) {
            return new ModelsResult(false, List.of(), "加载失败：" + ex.getClass().getSimpleName());
        }
    }

    /**
     * 可用性：baseUrl 与 model 必须非空；本地 provider 允许没有 Key，云端 provider 仍要 Key。
     * 这样全局默认的 DeepSeek（没有 Key）不会被误报成“已配置”。
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
}
