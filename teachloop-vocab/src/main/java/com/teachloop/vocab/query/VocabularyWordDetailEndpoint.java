package com.teachloop.vocab.query;

import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.content.ContentView;
import com.teachloop.vocab.importer.WordImportService;
import com.teachloop.vocab.lexicon.WordFamily;
import com.teachloop.vocab.lexicon.WordFamilyIndex;
import com.teachloop.vocab.word.VocabWordRepository;
import com.teachloop.vocab.word.WordNormalizer;
import java.math.BigDecimal;
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
    private final ObjectMapper mapper = new ObjectMapper();

    public VocabularyWordDetailEndpoint(VocabWordRepository words, WordFamilyIndex lexicon,
                                        WordImportService imports, ContentStore contentStore) {
        this.words = words;
        this.lexicon = lexicon;
        this.imports = imports;
        this.contentStore = contentStore;
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

    @GetMapping("/words/by-form/{form}")
    public DetailView byForm(@PathVariable String form,
                             @RequestParam(required = false) String language,
                             @RequestAttribute("currentUser") CurrentUser me) {
        String lang = language == null || language.isBlank() ? "en" : language;
        String normalized = WordNormalizer.normalized(form);
        FamilyView family = familyOf(WordNormalizer.display(form));
        return words.detail(me.id(), lang, normalized)
                .map(row -> new DetailView(true, row.id(), row.form(), row.language(),
                        row.contentStatus(), row.favorite(), row.paused(), parseTags(row.tagsJson()),
                        row.state(), row.dueAt(), row.intervalDays(), row.ease(),
                        ContentView.resolve(contentStore, row.contentId(), row.language(), row.form()),
                        sources(me.id(), row.id()), family))
                .orElseGet(() -> {
                    // 不在库的词也能查：全局内容命中（包/delta）就展示，只是不进复习队列
                    var content = ContentView.resolve(contentStore, null, lang, normalized);
                    return new DetailView(false, null, WordNormalizer.display(form), lang,
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
