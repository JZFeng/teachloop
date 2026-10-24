package com.teachloop.vocab.sets;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.lexicon.WordFamily;
import com.teachloop.vocab.lexicon.WordFamilyIndex;
import com.teachloop.vocab.sets.VocabularySetRepository.SetView;
import com.teachloop.vocab.sets.VocabularySetRepository.SetWordView;
import com.teachloop.vocab.word.WordNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师词集后端（T406 / US-49）。词集是教师侧内容资产：一条词集可反复布置，
 * 词条顺序即学生首次预习顺序；「移除」只把词移出词集，不删词条、不影响学生词库。
 *
 * <p>只允许 TEACHER / ADMIN；不属于自己的词集按 404 处理（AC-54.2）。
 *
 * <p>分层：HTTP 形状、字段校验、词表归一化与词族解析留在这里；SQL 在
 * {@link VocabularySetRepository}。
 */
@RestController
@RequestMapping("/api/vocab/sets")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class VocabularySetEndpoint {

    private static final int MAX_BATCH = 200;

    private final VocabularySetRepository sets;
    private final WordFamilyIndex lexicon;
    private final Clock clock;
    private final int maxWordsPerSet;

    public VocabularySetEndpoint(VocabularySetRepository sets, WordFamilyIndex lexicon, Clock clock,
                                 @Value("${teachloop.vocab.sets.max-words:2000}") int maxWordsPerSet) {
        this.sets = sets;
        this.lexicon = lexicon;
        this.clock = clock;
        this.maxWordsPerSet = Math.max(1, maxWordsPerSet);
    }

    public record SetDetail(SetView set, List<SetWordView> words) {
    }

    public record CreateRequest(String name, String language, String description) {
    }

    public record UpdateRequest(String name, String language, String description, Boolean active) {
    }

    public record BatchRequest(List<UUID> ids, String action) {
    }

    public record AddWordsRequest(List<String> words) {
    }

    public record AddWordsResult(int added, int skipped) {
    }

    public record OrderRequest(List<UUID> wordIds) {
    }

    public record Affected(int affected) {
    }

    @GetMapping
    public List<SetView> list(@RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        return sets.listByTeacher(me.id());
    }

    @PostMapping
    public SetView create(@RequestBody(required = false) CreateRequest request,
                          @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        String name = validName(request == null ? null : request.name());
        String language = request == null || request.language() == null || request.language().isBlank()
                ? "en" : request.language();
        UUID id = UUID.randomUUID();
        try {
            sets.create(id, me.id(), language, name,
                    request == null ? null : request.description(), clock.instant());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("a word set with this name already exists");
        }
        return requireOwned(id, me.id());
    }

    @GetMapping("/{id}")
    public SetDetail detail(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        SetView set = requireOwned(id, me.id());
        return new SetDetail(set, sets.wordsOf(id));
    }

    @PutMapping("/{id}")
    public SetView update(@PathVariable UUID id, @RequestBody(required = false) UpdateRequest request,
                          @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        SetView current = requireOwned(id, me.id());
        String name = request == null || request.name() == null ? current.name() : validName(request.name());
        String language = request == null || request.language() == null || request.language().isBlank()
                ? current.language() : request.language();
        String description = request == null || request.description() == null
                ? current.description() : request.description();
        boolean active = request == null || request.active() == null ? current.active() : request.active();
        try {
            sets.updateMeta(id, me.id(), name, language, description, active, clock.instant());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("a word set with this name already exists");
        }
        return requireOwned(id, me.id());
    }

    @DeleteMapping("/{id}")
    public Affected delete(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        int updated = sets.softDelete(id, me.id(), clock.instant());
        if (updated == 0) {
            throw ApiException.notFound("word set not found");
        }
        return new Affected(updated);
    }

    @PostMapping("/batch")
    public Affected batch(@RequestBody(required = false) BatchRequest request,
                          @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw ApiException.badRequest("ids is required");
        }
        if (request.ids().size() > MAX_BATCH) {
            throw ApiException.badRequest("at most " + MAX_BATCH + " ids per batch");
        }
        Instant now = clock.instant();
        int affected = 0;
        for (UUID id : request.ids()) {
            affected += switch (request.action() == null ? "" : request.action()) {
                case "ACTIVATE" -> sets.setActive(id, me.id(), true, now);
                case "DEACTIVATE" -> sets.setActive(id, me.id(), false, now);
                case "DELETE" -> sets.softDeleteIfActive(id, me.id(), now);
                default -> throw ApiException.badRequest("action must be ACTIVATE, DEACTIVATE or DELETE");
            };
        }
        return new Affected(affected);
    }

    @PostMapping("/{id}/words")
    @Transactional
    public AddWordsResult addWords(@PathVariable UUID id, @RequestBody(required = false) AddWordsRequest request,
                                   @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        requireOwned(id, me.id());
        int existing = sets.countWords(id);
        int position = sets.maxPosition(id);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int added = 0;
        int skipped = 0;
        for (String raw : request == null ? List.<String>of() : request.words()) {
            if (raw == null || raw.isBlank()) {
                skipped++;
                continue;
            }
            String form = WordNormalizer.display(raw);
            String normalized = WordNormalizer.normalized(form);
            if (!WordNormalizer.valid(form) || !seen.add(normalized)) {
                skipped++;
                continue;
            }
            if (existing + added >= maxWordsPerSet) {
                skipped++;
                continue;
            }
            WordFamily family = lexicon.lookup(form).orElse(null);
            int inserted = sets.insertWord(UUID.randomUUID(), id, ++position, form, normalized,
                    family == null ? null : family.familyId(),
                    family == null ? null : family.band(), clock.instant());
            if (inserted == 0) {
                skipped++;
            } else {
                added++;
            }
        }
        sets.touch(id, clock.instant());
        return new AddWordsResult(added, skipped);
    }

    @DeleteMapping("/{id}/words/{wordId}")
    public Affected removeWord(@PathVariable UUID id, @PathVariable UUID wordId,
                               @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        requireOwned(id, me.id());
        int removed = sets.removeWord(wordId, id);
        if (removed == 0) {
            throw ApiException.notFound("word not in this set");
        }
        sets.touch(id, clock.instant());
        return new Affected(removed);
    }

    @PutMapping("/{id}/words/order")
    @Transactional
    public Affected reorder(@PathVariable UUID id, @RequestBody(required = false) OrderRequest request,
                            @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        requireOwned(id, me.id());
        int position = 0;
        for (UUID wordId : request == null || request.wordIds() == null ? List.<UUID>of() : request.wordIds()) {
            position++;
            sets.updateWordPosition(wordId, id, position);
        }
        sets.touch(id, clock.instant());
        return new Affected(position);
    }

    private SetView requireOwned(UUID id, UUID teacherId) {
        return sets.findOwned(id, teacherId)
                .orElseThrow(() -> ApiException.notFound("word set not found"));
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 100) {
            throw ApiException.badRequest("name is required (<=100 chars)");
        }
        return name;
    }

    private static void requireTeacher(CurrentUser me) {
        if (!me.is("TEACHER") && !me.is("ADMIN")) {
            throw ApiException.forbidden("teacher only");
        }
    }
}
