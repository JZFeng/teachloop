package com.teachloop.vocab.query;

import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.content.ContentView;
import com.teachloop.vocab.generation.GenerationJobStore;
import com.teachloop.vocab.generation.VocabularyGenerationService;
import com.teachloop.vocab.importer.WordImportService;
import com.teachloop.vocab.lexicon.WordFamily;
import com.teachloop.vocab.lexicon.WordFamilyIndex;
import com.teachloop.vocab.word.VocabWordRepository;
import com.teachloop.vocab.word.WordNormalizer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 单词详情页的数据接口（T399 / US-60）：模板 + JSON，同一个端点同时服务
 * 「在库」与「不在库」两种状态。
 *
 * <p>在库：词条 content + 进度 + 来源 + 标签；不在库：只回词形与本地词族（命中才给频段），
 * 前端据此给「＋ 加入词库」。词族本地优先（T388 索引），未命中不编造频段（AC-60.7）。
 */
@RestController
@RequestMapping("/api/vocab")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class VocabularyWordDetailEndpoint {

    private final VocabWordRepository words;
    private final WordFamilyIndex lexicon;
    private final WordImportService imports;
    private final ContentStore contentStore;
    private final GenerationJobStore jobs;
    private final VocabularyGenerationService generation;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public VocabularyWordDetailEndpoint(VocabWordRepository words, WordFamilyIndex lexicon,
                                        WordImportService imports, ContentStore contentStore,
                                        GenerationJobStore jobs,
                                        VocabularyGenerationService generation, Clock clock) {
        this.words = words;
        this.lexicon = lexicon;
        this.imports = imports;
        this.contentStore = contentStore;
        this.jobs = jobs;
        this.generation = generation;
        this.clock = clock;
    }

    public record MemberView(String form, long freq, boolean head) {
    }

    public record FamilyView(String head, long familyId, int band, long familyFreq,
                             boolean matchedFolded, List<MemberView> members) {
    }

    public record SourceView(String kind, UUID batchId, UUID assignmentId, String label) {
    }

    public record DetailView(boolean inLibrary, UUID id, String form, String language,
                             String contentStatus, boolean favorite, boolean paused, List<String> tags,
                             String state, Instant dueAt, BigDecimal intervalDays, BigDecimal ease,
                             JsonNode content, List<SourceView> sources, FamilyView family) {
    }

    public record AddRequest(String form, String language) {
    }

    public record AddResult(UUID id, int created, int duplicated) {
    }

    public record FeedbackRequest(String note) {
    }

    public record FeedbackResult(long id) {
    }

    public record LookupRequest(String form, String language, Boolean retry) {
    }

    /** 查词状态：READY=内容已在；PENDING=已入队/正在生成；FAILED=生成失败；DISABLED=查词即生成被关掉。 */
    public record LookupResult(String state, DetailView detail) {
    }

    @GetMapping("/words/by-form/{form}")
    public DetailView byForm(@PathVariable String form,
                             @RequestParam(required = false) String language,
                             @RequestAttribute("currentUser") CurrentUser me) {
        String lang = language == null || language.isBlank() ? "en" : language;
        return view(me, form, lang);
    }

    /**
     * 查词即生成（T515 / AC-60.13，2026-10-26 用户 Bug：「查词为啥不直接接 AI 查询，然后跳转到 card back」）。
     *
     * <p>不在库的词不再要求先点「＋ 加入词库」：全局内容没有命中时，直接为这个**词形**入队一次 AI 生成，
     * 生成结果只写全局 {@code ContentStore}，不建词条、不进复习队列。前端拿到 {@code PENDING} 后每 2 秒
     * 再调一次本接口，直到 {@code READY} 把内容渲染在卡片反面。
     *
     * <p>幂等：同一词形只会有一条 FORM 任务；{@code retry=true} 只在用户点「重试」时传，
     * 否则失败任务不会被页面轮询自动重置（避免一条生成不了的词无限烧 AI）。
     */
    @PostMapping("/words/lookup")
    public LookupResult lookup(@RequestBody(required = false) LookupRequest request,
                               @RequestAttribute("currentUser") CurrentUser me) {
        String form = request == null || request.form() == null ? "" : request.form().strip();
        String lang = request == null || request.language() == null || request.language().isBlank()
                ? "en" : request.language();
        if (!WordNormalizer.valid(form)) {
            throw com.teachloop.shared.error.ApiException.badRequest("form is required");
        }
        DetailView detail = view(me, form, lang);
        if (detail.content() != null) {
            return new LookupResult("READY", detail);
        }
        if (detail.inLibrary()) {
            // 在库的词由详情页自己的 prepare 流程推进（T426），查词接口不重复入队。
            return new LookupResult("PENDING", detail);
        }
        if (!generation.lookupEnabled()) {
            return new LookupResult("DISABLED", detail);
        }
        String state = jobs.formState(lang, WordNormalizer.normalized(form)).orElse(null);
        if ("FAILED".equals(state)) {
            if (request != null && Boolean.TRUE.equals(request.retry())) {
                jobs.retryForm(lang, form, clock.instant());
                return new LookupResult("PENDING", detail);
            }
            return new LookupResult("FAILED", detail);
        }
        if (state == null) {
            generation.prepareLookup(lang, form);
        }
        return new LookupResult("PENDING", detail);
    }

    /** 两种状态共用一份视图：在库 = 词条 + 进度 + 来源；不在库 = 全局内容（有就展示）+ 词族。 */
    private DetailView view(CurrentUser me, String form, String language) {
        String normalized = WordNormalizer.normalized(form);
        FamilyView family = familyOf(WordNormalizer.display(form));
        return words.detail(me.id(), language, normalized)
                .map(row -> {
                    var content = ContentView.resolve(contentStore, row.contentId(),
                            row.language(), row.form());
                    // 查词页与复习卡同一口径：库里过期的 READY 不能当「有内容」，否则页面是空卡
                    //（2026-10-29 用户报 Bug：内容包换版后所有卡都没有释义）。
                    return new DetailView(true, row.id(), row.form(), row.language(),
                            ContentView.statusFor(row.contentStatus(), content), row.favorite(),
                            row.paused(), parseTags(row.tagsJson()), row.state(), row.dueAt(),
                            row.intervalDays(), row.ease(), content,
                            sources(me.id(), row.id()), family);
                })
                .orElseGet(() -> {
                    // 不在库的词也能查：全局内容命中（包/delta）就展示，只是不进复习队列
                    var content = ContentView.resolve(contentStore, null, language, normalized);
                    return new DetailView(false, null, WordNormalizer.display(form), language,
                            content != null ? "READY" : null, false, false, List.of(),
                            null, null, null, null, content, List.of(), family);
                });
    }

    @PostMapping("/words")
    public AddResult add(@RequestBody(required = false) AddRequest request,
                         @RequestAttribute("currentUser") CurrentUser me) {
        String form = request == null || request.form() == null ? "" : request.form();
        String lang = request == null || request.language() == null || request.language().isBlank()
                ? "en" : request.language();
        WordImportService.CommitResult result = imports.commit(me.id(), lang, form, null);
        UUID id = words.detail(me.id(), lang, WordNormalizer.normalized(form))
                .map(VocabWordRepository.WordDetailRow::id).orElse(null);
        return new AddResult(id, result.counts().created(), result.counts().duplicated());
    }

    @PostMapping("/words/{id}/feedback")
    public FeedbackResult feedback(@PathVariable UUID id,
                                   @RequestBody(required = false) FeedbackRequest request,
                                   @RequestAttribute("currentUser") CurrentUser me) {
        if (!words.belongsTo(me.id(), id)) {
            throw com.teachloop.shared.error.ApiException.notFound("word not found");
        }
        String note = request == null || request.note() == null ? "" : request.note().strip();
        // 只带 word id + 可选备注；服务端按 id 保留提交时的上下文快照（AC-60.4）。
        String payload = mapper.writeValueAsString(java.util.Map.of("note", note));
        return new FeedbackResult(words.addFeedback(me.id(), id, payload));
    }

    private List<SourceView> sources(UUID userId, UUID wordId) {
        return words.sourcesOf(wordId).stream()
                .map(source -> new SourceView(source.kind(), source.batchId(),
                        source.assignmentId(), source.label()))
                .toList();
    }

    private FamilyView familyOf(String form) {
        return lexicon.lookup(form)
                .map(this::family)
                .orElse(null);
    }

    private FamilyView family(WordFamily hit) {
        return new FamilyView(hit.head(), hit.familyId(), hit.band(), hit.familyFreq(),
                hit.matchedFolded(), hit.members().stream()
                .map(member -> new MemberView(member.form(), member.freq(), member.head()))
                .toList());
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> tags = new java.util.ArrayList<>();
        for (JsonNode tag : mapper.readTree(json)) {
            if (tag.isString()) {
                tags.add(tag.asString());
            }
        }
        return tags;
    }
}
