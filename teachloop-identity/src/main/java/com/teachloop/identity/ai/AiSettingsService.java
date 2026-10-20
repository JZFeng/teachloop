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
import tools.jackson.databind.ObjectMapper;

/** 管理员 AI 配置：保存（Key 加密）、读取（掩码）、连通性测试。 */
@Service
public class AiSettingsService {

    public record View(String provider, String baseUrl, String model, String temperature,
                       boolean apiKeySet, String apiKeyMasked, String updatedAt, boolean restartRequired) {
    }

    public record UpdateRequest(String provider, String baseUrl, String model, String apiKey,
                                BigDecimal temperature) {
    }

    public record TestRequest(String baseUrl, String model, String apiKey) {
    }

    public record TestResult(boolean ok, long latencyMs, String message, String model) {
    }

    private final AiSettingsRepository repo;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AiSettingsService(AiSettingsRepository repo, SecretBox secretBox, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.secretBox = secretBox;
        this.mapper = mapper;
        this.clock = clock;
    }

    public View view() {
        AiSettingsEntity e = repo.findById(1).orElseThrow(() -> ApiException.notFound("AI 配置不存在"));
        String key = secretBox.decrypt(e.getApiKeyCipher());
        return new View(e.getProvider(), e.getBaseUrl(), e.getModel(),
                e.getTemperature().stripTrailingZeros().toPlainString(),
                key != null && !key.isBlank(), mask(key), e.getUpdatedAt().toString(), false);
    }

    public Optional<Ports.AiSettings> lookup() {
        return repo.findById(1).map(e -> {
            String key = secretBox.decrypt(e.getApiKeyCipher());
            return new Ports.AiSettings(e.getProvider(), e.getBaseUrl(), e.getModel(), key,
                    e.getTemperature(), key != null && !key.isBlank());
        });
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
                req.apiKey() != null && !req.apiKey().isBlank(), mask(req.apiKey()),
                e.getUpdatedAt().toString(), true);
    }

    /** 用提交值或已保存值测试；异常也转成 ok=false，不把 Key 写日志。 */
    public TestResult test(TestRequest req) {
        AiSettingsEntity e = repo.findById(1).orElseThrow();
        String baseUrl = req.baseUrl() == null || req.baseUrl().isBlank() ? e.getBaseUrl() : req.baseUrl().strip();
        String model = req.model() == null || req.model().isBlank() ? e.getModel() : req.model().strip();
        String key = req.apiKey() == null || req.apiKey().isBlank()
                ? secretBox.decrypt(e.getApiKeyCipher()) : req.apiKey().strip();
        if (key == null || key.isBlank()) {
            return new TestResult(false, 0, "还没有保存 API Key", model);
        }
        long started = System.nanoTime();
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "temperature", 0.2,
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new TestResult(true, ms, "连接成功（HTTP 200）", model);
            }
            String hint = response.statusCode() == 401 || response.statusCode() == 403
                    ? "认证失败，检查 API Key" : "HTTP " + response.statusCode();
            return new TestResult(false, ms, hint, model);
        } catch (Exception ex) {
            long ms = (System.nanoTime() - started) / 1_000_000;
            return new TestResult(false, ms, "连接失败：" + ex.getClass().getSimpleName(), model);
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
