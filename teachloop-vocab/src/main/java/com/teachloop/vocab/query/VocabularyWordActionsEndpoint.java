package com.teachloop.vocab.query;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.generation.GenerationJobStore;
import com.teachloop.vocab.word.VocabWordRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 词库行内/批量动作、统计卡数据与列配置（T397）。
 *
 * <p>所有写操作都以 `user_id` 圈定范围：不属于自己的词返回 404（AC-54.1）。
 * 暂停/恢复/删除的语义见 spec §19：暂停不删词、恢复回新词、删除是软删除。
 */
@RestController
@RequestMapping("/api/vocab")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class VocabularyWordActionsEndpoint {

    private static final int MAX_BATCH = 200;

    private final VocabWordRepository words;
    private final GenerationJobStore jobs;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 内容生成要真模型；无 identity 装配时放行。 */
    private final ObjectProvider<Ports.AiSettingsLookup> aiSettings;
    /**
     * 内容生成自己的引擎开关：stub 是确定性替身（{@code StubVocabularyContentGenerator}），
     * 不需要 Key 也能生成，重试不应被拦；只有 engine=ai 才要求 configured。
     */
    private final String generationEngine;

    public VocabularyWordActionsEndpoint(VocabWordRepository words, GenerationJobStore jobs,
                                         ObjectProvider<Ports.AiSettingsLookup> aiSettings,
                                         @Value("${teachloop.vocab.generation.engine:ai}") String generationEngine) {
        this.words = words;
        this.jobs = jobs;
        this.aiSettings = aiSettings;
        this.generationEngine = generationEngine;
    }

    public record BatchRequest(List<UUID> ids, String action) {
    }

    public record FavoriteRequest(boolean favorite) {
    }

    public record RetryRequest(List<UUID> ids) {
    }

    public record Affected(int affected) {
    }

    public record StatsView(long total, long dueToday, long newWords, long mastered, long paused,
                            long favorite) {
    }

    public record SourceView(String kind, UUID batchId, UUID assignmentId, String label, long words) {
    }

    public record PrefsView(JsonNode tableColumns) {
    }

    @PostMapping("/words/{id}/pause")
    public Affected pause(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        return new Affected(words.pause(me.id(), id) ? 1 : notFound());
    }

    @PostMapping("/words/{id}/resume")
    public Affected resume(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        return new Affected(words.resume(me.id(), id) ? 1 : notFound());
    }

    @DeleteMapping("/words/{id}")
    public Affected delete(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        return new Affected(words.delete(me.id(), id) ? 1 : notFound());
    }

    @PutMapping("/words/{id}/favorite")
    public Affected favorite(@PathVariable UUID id, @RequestBody FavoriteRequest request,
                             @RequestAttribute("currentUser") CurrentUser me) {
        if (request == null) {
            throw ApiException.badRequest("favorite is required");
        }
        return new Affected(words.setFavorite(me.id(), id, request.favorite()) ? 1 : notFound());
    }

    /** T395：单条/批量重试内容生成（词条回 PENDING，job 回 QUEUED）。 */
    @PostMapping("/words/retry")
    public Affected retry(@RequestBody(required = false) RetryRequest request,
                          @RequestAttribute("currentUser") CurrentUser me) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw ApiException.badRequest("ids is required");
        }
        if (request.ids().size() > MAX_BATCH) {
            throw ApiException.badRequest("at most " + MAX_BATCH + " ids per batch");
        }
        for (UUID id : request.ids()) {
            if (!words.belongsTo(me.id(), id)) {
                throw ApiException.notFound("word not found");
            }
        }
        requireAiConfigured();
        for (UUID id : request.ids()) {
            jobs.retryWord(id, java.time.Instant.now());
        }
        return new Affected(request.ids().size());
    }

    @PostMapping("/words/batch")
    public Affected batch(@RequestBody BatchRequest request,
                          @RequestAttribute("currentUser") CurrentUser me) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw ApiException.badRequest("ids is required");
        }
        if (request.ids().size() > MAX_BATCH) {
            throw ApiException.badRequest("at most " + MAX_BATCH + " ids per batch");
        }
        return new Affected(words.batch(me.id(), request.ids(), request.action()));
    }

    @GetMapping("/words/stats")
    public StatsView stats(@RequestParam(required = false) String language,
                           @RequestAttribute("currentUser") CurrentUser me) {
        VocabWordRepository.Stats stats = words.stats(me.id(), languageOrDefault(language));
        return new StatsView(stats.total(), stats.dueToday(), stats.newWords(),
                stats.mastered(), stats.paused(), stats.favorite());
    }

    @GetMapping("/sources")
    public List<SourceView> sources(@RequestParam(required = false) String language,
                                    @RequestAttribute("currentUser") CurrentUser me) {
        return words.sources(me.id(), languageOrDefault(language)).stream()
                .map(source -> new SourceView(source.kind(), source.batchId(),
                        source.assignmentId(), source.label(), source.words()))
                .toList();
    }

    @GetMapping("/prefs")
    public PrefsView prefs(@RequestAttribute("currentUser") CurrentUser me) {
        String json = words.prefs(me.id());
        return new PrefsView(json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json));
    }

    @PutMapping("/prefs")
    public PrefsView savePrefs(@RequestBody(required = false) PrefsView request,
                               @RequestAttribute("currentUser") CurrentUser me) {
        JsonNode tableColumns = request == null || request.tableColumns() == null
                ? mapper.createObjectNode() : request.tableColumns();
        words.savePrefs(me.id(), mapper.writeValueAsString(tableColumns));
        return new PrefsView(tableColumns);
    }

    /**
     * 内容生成失败时重试只会再失败一次；拦在把词条改回 PENDING 之前，让界面提示联系管理员。
     * stub 生成器能在没有 Key 时出内容，所以只看引擎：ai 才要求 configured。
     */
    private void requireAiConfigured() {
        if ("stub".equalsIgnoreCase(generationEngine == null ? "" : generationEngine.strip())) {
            return;
        }
        Ports.AiSettingsLookup lookup = aiSettings.getIfAvailable();
        if (lookup == null) {
            return;
        }
        if (!lookup.readiness().configured()) {
            throw ApiException.conflict("error.ai.notConfigured");
        }
    }

    private static int notFound() {
        throw ApiException.notFound("word not found");
    }

    private static String languageOrDefault(String language) {
        return language == null || language.isBlank() ? "en" : language;
    }
}
