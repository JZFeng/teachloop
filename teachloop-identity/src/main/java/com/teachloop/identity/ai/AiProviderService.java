package com.teachloop.identity.ai;

import com.teachloop.shared.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员维护的全局 provider 列表（决策 010 修订）。
 *
 * <p>v1 只允许 OpenAI 兼容协议；一个 provider 保存一个当前模型。教师端只读这个列表并切换
 * {@code ai_settings.active_provider_id}，不接触 Key 与地址。
 */
@Service
public class AiProviderService {

    public record View(String id, String providerId, String displayName, String baseUrl, String protocol,
                       String model, boolean apiKeySet, String apiKeyMasked, boolean active, String updatedAt) {
    }

    public record Request(String providerId, String displayName, String baseUrl, String protocol,
                          String apiKey, String model) {
    }

    public record Option(String providerId, String displayName, String model, String label) {
    }

    public record Options(List<Option> options, String currentProviderId) {
    }

    private final AiProviderRepository repo;
    private final AiSettingsService aiSettings;
    private final SecretBox secretBox;
    private final Clock clock;

    public AiProviderService(AiProviderRepository repo, AiSettingsService aiSettings,
                             SecretBox secretBox, Clock clock) {
        this.repo = repo;
        this.aiSettings = aiSettings;
        this.secretBox = secretBox;
        this.clock = clock;
    }

    public List<View> list() {
        UUID active = aiSettings.activeProviderId();
        return repo.findAllByOrderByDisplayNameAsc().stream()
                .map(p -> view(p, active != null && active.equals(p.getId())))
                .toList();
    }

    public Options options() {
        UUID active = aiSettings.activeProviderId();
        List<Option> options = repo.findAllByOrderByDisplayNameAsc().stream()
                .map(p -> new Option(p.getProviderKey(), p.getDisplayName(), p.getModel(),
                        p.getDisplayName() + " · " + p.getModel()))
                .toList();
        String current = active == null ? null : repo.findById(active)
                .map(AiProviderEntity::getProviderKey).orElse(null);
        return new Options(options, current);
    }

    @Transactional
    public View create(UUID actorId, Request req) {
        String providerKey = validProviderKey(req.providerId());
        if (repo.findByProviderKey(providerKey).isPresent()) {
            throw ApiException.conflict("error.ai.providerExists");
        }
        Instant now = clock.instant();
        AiProviderEntity p = new AiProviderEntity(UUID.randomUUID(), providerKey,
                validDisplayName(req.displayName()), validBaseUrl(req.baseUrl()),
                validProtocol(req.protocol()), encrypt(req.apiKey()), validModel(req.model()),
                now, now, actorId);
        repo.save(p);
        // 第一次添加的 provider 直接成为当前模型，避免教师下拉为空。
        if (aiSettings.activeProviderId() == null) {
            aiSettings.setActiveProvider(p.getId(), actorId);
        }
        return view(p, p.getId().equals(aiSettings.activeProviderId()));
    }

    @Transactional
    public View update(UUID actorId, UUID id, Request req) {
        AiProviderEntity p = repo.findById(id).orElseThrow(() -> ApiException.notFound("error.ai.providerNotFound"));
        String providerKey = validProviderKey(req.providerId());
        repo.findByProviderKey(providerKey)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw ApiException.conflict("error.ai.providerExists"); });
        p.setProviderKey(providerKey);
        p.setDisplayName(validDisplayName(req.displayName()));
        p.setBaseUrl(validBaseUrl(req.baseUrl()));
        p.setProtocol(validProtocol(req.protocol()));
        p.setModel(validModel(req.model()));
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            p.setApiKeyCipher(secretBox.encrypt(req.apiKey().strip()));
        }
        p.setUpdatedAt(clock.instant());
        p.setUpdatedBy(actorId);
        repo.save(p);
        return view(p, p.getId().equals(aiSettings.activeProviderId()));
    }

    @Transactional
    public void delete(UUID actorId, UUID id) {
        if (!repo.existsById(id)) {
            throw ApiException.notFound("error.ai.providerNotFound");
        }
        aiSettings.clearActiveIf(id, actorId);
        repo.deleteById(id);
    }

    @Transactional
    public Options select(UUID actorId, String providerId) {
        AiProviderEntity p = repo.findByProviderKey(providerId)
                .orElseThrow(() -> ApiException.notFound("error.ai.providerNotFound"));
        aiSettings.setActiveProvider(p.getId(), actorId);
        return options();
    }

    public AiSettingsService.ModelsResult models(AiSettingsService.TestRequest req) {
        return aiSettings.listModels(req);
    }

    public AiSettingsService.TestResult test(AiSettingsService.TestRequest req) {
        return aiSettings.test(req);
    }

    private View view(AiProviderEntity p, boolean active) {
        String key = secretBox.decrypt(p.getApiKeyCipher());
        return new View(p.getId().toString(), p.getProviderKey(), p.getDisplayName(), p.getBaseUrl(),
                p.getProtocol(), p.getModel(), key != null && !key.isBlank(), mask(key), active,
                p.getUpdatedAt().toString());
    }

    private static String validProviderKey(String raw) {
        if (raw == null || !raw.strip().matches("^[a-z][a-z0-9._-]{0,79}$")) {
            throw ApiException.badRequest("error.ai.providerId");
        }
        return raw.strip();
    }

    private static String validDisplayName(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().length() > 100) {
            throw ApiException.badRequest("error.ai.displayName");
        }
        return raw.strip();
    }

    private static String validBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("error.ai.baseUrl");
        }
        String url = raw.strip();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw ApiException.badRequest("error.ai.baseUrl");
        }
        return url;
    }

    private static String validProtocol(String raw) {
        if (raw == null || raw.isBlank()) {
            return "openai";
        }
        String p = raw.strip().toLowerCase();
        if (!p.equals("openai") && !p.equals("openai-chat")) {
            throw ApiException.badRequest("error.ai.protocol");
        }
        return "openai";
    }

    private static String validModel(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().length() > 100) {
            throw ApiException.badRequest("error.ai.model");
        }
        return raw.strip();
    }

    private String encrypt(String key) {
        return key == null || key.isBlank() ? null : secretBox.encrypt(key.strip());
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
