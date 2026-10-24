package com.teachloop.identity.ai;

import com.teachloop.shared.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理员维护的全局 provider 列表（决策 010 修订）。
 *
 * <p>v1 只允许 OpenAI 兼容协议；一个 provider 可以保存多个模型，但全站只有一个
 * 「当前 provider + 当前模型」。教师只在下拉里选，不接触地址与 Key。
 */
@Service
public class AiProviderService {

    public record View(String id, String providerId, String displayName, String baseUrl, String protocol,
                       String model, List<String> models, boolean apiKeySet, String apiKeyMasked,
                       boolean active, String updatedAt) {
    }

    public record Request(String providerId, String displayName, String baseUrl, String protocol,
                          String apiKey, String model, List<String> models) {
    }

    public record Option(String key, String providerId, String displayName, String model, String label) {
    }

    public record Options(List<Option> options, String currentProviderId, String currentModel,
                          String currentKey) {
    }

    private final AiProviderRepository repo;
    private final AiSettingsService aiSettings;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AiProviderService(AiProviderRepository repo, AiSettingsService aiSettings,
                             SecretBox secretBox, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.aiSettings = aiSettings;
        this.secretBox = secretBox;
        this.mapper = mapper;
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
        String currentModel = aiSettings.activeModel();
        List<Option> options = new ArrayList<>();
        for (AiProviderEntity p : repo.findAllByOrderByDisplayNameAsc()) {
            for (String model : modelsOf(p)) {
                options.add(new Option(key(p.getProviderKey(), model), p.getProviderKey(),
                        p.getDisplayName(), model, p.getDisplayName() + " · " + model));
            }
        }
        String currentProvider = active == null ? null : repo.findById(active)
                .map(AiProviderEntity::getProviderKey).orElse(null);
        if (currentModel == null && currentProvider != null) {
            currentModel = repo.findById(active).map(AiProviderEntity::getModel).orElse(null);
        }
        String currentKey = currentProvider == null || currentModel == null
                ? null : key(currentProvider, currentModel);
        return new Options(options, currentProvider, currentModel, currentKey);
    }

    @Transactional
    public View create(UUID actorId, Request req) {
        String providerKey = validProviderKey(req.providerId());
        if (repo.findByProviderKey(providerKey).isPresent()) {
            throw ApiException.conflict("error.ai.providerExists");
        }
        List<String> models = validModels(req.model(), req.models());
        Instant now = clock.instant();
        AiProviderEntity p = new AiProviderEntity(UUID.randomUUID(), providerKey,
                validDisplayName(req.displayName()), validBaseUrl(req.baseUrl()),
                validProtocol(req.protocol()), encrypt(req.apiKey()), models.get(0),
                now, now, actorId);
        p.setModelsJson(json(models));
        repo.save(p);
        // 第一次添加的 provider 直接成为当前模型，避免教师下拉为空。
        if (aiSettings.activeProviderId() == null) {
            aiSettings.setActiveProvider(p.getId(), models.get(0), actorId);
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
        List<String> models = validModels(req.model(), req.models());
        p.setProviderKey(providerKey);
        p.setDisplayName(validDisplayName(req.displayName()));
        p.setBaseUrl(validBaseUrl(req.baseUrl()));
        p.setProtocol(validProtocol(req.protocol()));
        p.setModel(models.get(0));
        p.setModelsJson(json(models));
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            p.setApiKeyCipher(secretBox.encrypt(req.apiKey().strip()));
        }
        p.setUpdatedAt(clock.instant());
        p.setUpdatedBy(actorId);
        repo.save(p);
        // 当前选中的模型被删掉时，回退到第一个模型。
        if (p.getId().equals(aiSettings.activeProviderId())) {
            String current = aiSettings.activeModel();
            if (current == null || !models.contains(current)) {
                aiSettings.setActiveProvider(p.getId(), models.get(0), actorId);
            }
        }
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
    public Options select(UUID actorId, String providerId, String model) {
        AiProviderEntity p = repo.findByProviderKey(providerId)
                .orElseThrow(() -> ApiException.notFound("error.ai.providerNotFound"));
        List<String> models = modelsOf(p);
        String selected = model == null || model.isBlank() ? models.get(0) : model.strip();
        if (!models.contains(selected)) {
            throw ApiException.badRequest("error.ai.model");
        }
        aiSettings.setActiveProvider(p.getId(), selected, actorId);
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
        List<String> models = modelsOf(p);
        return new View(p.getId().toString(), p.getProviderKey(), p.getDisplayName(), p.getBaseUrl(),
                p.getProtocol(), p.getModel(), models, key != null && !key.isBlank(), mask(key), active,
                p.getUpdatedAt().toString());
    }

    private List<String> modelsOf(AiProviderEntity p) {
        if (p.getModelsJson() != null && !p.getModelsJson().isBlank()) {
            try {
                List<String> parsed = mapper.readValue(p.getModelsJson(),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed;
                }
            } catch (RuntimeException ignored) {
                // 旧行/坏 JSON 回退到单 model 列。
            }
        }
        return List.of(p.getModel());
    }

    private String json(List<String> models) {
        return mapper.writeValueAsString(models);
    }

    private static String key(String providerId, String model) {
        return providerId + "::" + model;
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

    private static List<String> validModels(String legacyModel, List<String> raw) {
        Set<String> set = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw) {
                if (item != null && !item.isBlank()) {
                    set.add(item.strip());
                }
            }
        }
        if (set.isEmpty() && legacyModel != null && !legacyModel.isBlank()) {
            set.add(legacyModel.strip());
        }
        if (set.isEmpty()) {
            throw ApiException.badRequest("error.ai.model");
        }
        if (set.size() > 50 || set.stream().anyMatch(m -> m.length() > 100)) {
            throw ApiException.badRequest("error.ai.model");
        }
        return List.copyOf(set);
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
