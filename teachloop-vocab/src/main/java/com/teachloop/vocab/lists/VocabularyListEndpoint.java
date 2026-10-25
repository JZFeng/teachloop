package com.teachloop.vocab.lists;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.generation.VocabularyGenerationService;
import com.teachloop.vocab.lexicon.WordFamily;
import com.teachloop.vocab.lexicon.WordFamilyIndex;
import com.teachloop.vocab.lists.VocabularyListRepository.ListSize;
import com.teachloop.vocab.lists.VocabularyListRepository.ListView;
import com.teachloop.vocab.lists.VocabularyListRepository.ListWordPage;
import com.teachloop.vocab.word.VocabWordRepository;
import com.teachloop.vocab.word.WordNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生词单后端（T449 / US-69 / decisions 020 §6）。
 *
 * <p>词单是**只引用词条的容器**：词条仍按 {@code (user_id, language, normalized_form)} 只有一份，
 * 学习进度仍只有 {@code vocab_progress} 一行；同一个词进多份词单，复习后只有一份排期
 * （AC-69.1）。「加入 / 移出 / 删单」都不碰 `vocab_words` 与 `vocab_progress`。
 *
 * <p>全部接口都只作用于调用者自己：他人词单一律 **404**（不泄露存在性，AC-69.9）。
 * 与 decisions 020 §6 相比，本实现**多出两个只读接口**（契约以本类注释为准）：
 * <ul>
 *   <li>{@code GET /lists/{id}} —— 详情页的单条元数据（§6 只有列表与词分页，详情页要显示
 *       名称/描述/词数，逐份去列表里找是无谓的往返）。</li>
 *   <li>{@code GET /lists/{id}/review-words} —— 复习范围的轻量 id 数组（未暂停、未软删，
 *       按加入顺序），外加 {@code sessionLimit=1000}；超过时由前端分批调既有
 *       {@code POST /api/vocab/review-sessions}（其 {@code MAX_WORDS=1000}），不新增会话机制。
 *       §6 的替代做法是逐页拉 {@code /lists/{id}/words}，那样复习范围要么漏词要么 N 次往返。</li>
 * </ul>
 *
 * <p>分层：HTTP 形状、字段校验、词形归一化与「先入库再引用」留在这里；SQL 在
 * {@link VocabularyListRepository}，词条写入复用 {@link VocabWordRepository}。
 */
@RestController
@RequestMapping("/api/vocab/lists")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class VocabularyListEndpoint {

    private static final int MAX_NAME = 80;
    private static final int MAX_DESCRIPTION = 200;
    private static final int MAX_ADD_PER_REQUEST = 200;
    private static final int MAX_PAGE_SIZE = 200;
    /** 与 {@code ReviewSessionEndpoint.MAX_WORDS} 对齐：一次会话最多 1000 词。 */
    private static final int SESSION_WORD_LIMIT = 1000;
    private static final String DEFAULT_LANGUAGE = "en";

    private final VocabularyListRepository lists;
    private final VocabWordRepository words;
    private final WordFamilyIndex lexicon;
    private final VocabularyGenerationService generation;
    private final Clock clock;
    private final int maxPerStudent;

    public VocabularyListEndpoint(VocabularyListRepository lists, VocabWordRepository words,
                                  WordFamilyIndex lexicon, VocabularyGenerationService generation,
                                  Clock clock,
                                  @Value("${teachloop.vocab.lists.max-per-student:100}") int maxPerStudent) {
        this.lists = lists;
        this.words = words;
        this.lexicon = lexicon;
        this.generation = generation;
        this.clock = clock;
        this.maxPerStudent = Math.max(1, maxPerStudent);
    }

    public record CreateRequest(String name, String description) {
    }

    public record UpdateRequest(String name, String description) {
    }

    /**
     * 加词：二选一。{@code wordIds} 是库里已有的词（必须属于本人，否则 404）；
     * {@code forms} 是词形，先入库再引用（AC-69.5「一步合并」），{@code language} 省略按 en。
     */
    public record AddWordsRequest(List<UUID> wordIds, List<String> forms, String language) {
    }

    public record AddWordsResult(int added, int duplicated) {
    }

    public record Affected(int affected) {
    }

    /** 复习范围：未暂停、未软删的词 id（按加入顺序）；{@code sessionLimit} 供前端分批。 */
    public record ReviewScope(List<UUID> wordIds, int reviewable, int total, int paused, int deleted,
                              int sessionLimit) {
    }

    @GetMapping
    public List<ListView> list(@RequestAttribute("currentUser") CurrentUser me) {
        return lists.listByStudent(me.id());
    }

    @PostMapping
    public ListView create(@RequestBody(required = false) CreateRequest request,
                           @RequestAttribute("currentUser") CurrentUser me) {
        String name = validName(request == null ? null : request.name());
        String description = validDescription(request == null ? null : request.description());
        if (lists.countActive(me.id()) >= maxPerStudent) {
            // AC-69.4：达到上限时拒绝新建，已有词单不受影响（前端据此禁用创建按钮）。
            throw ApiException.conflict("list limit reached: at most " + maxPerStudent + " lists per student");
        }
        if (lists.nameTaken(me.id(), name, null)) {
            throw ApiException.conflict("a word list with this name already exists");
        }
        UUID id = UUID.randomUUID();
        try {
            lists.create(id, me.id(), name, description, lists.nextPosition(me.id()), clock.instant());
        } catch (DuplicateKeyException ex) {
            // 并发同名：库层唯一索引 uk_vocab_lists_user_name 兜底（AC-69.1），不静默改名。
            throw ApiException.conflict("a word list with this name already exists");
        }
        return requireOwned(id, me.id());
    }

    @GetMapping("/{id}")
    public ListView detail(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        return requireOwned(id, me.id());
    }

    @PutMapping("/{id}")
    public ListView update(@PathVariable UUID id, @RequestBody(required = false) UpdateRequest request,
                           @RequestAttribute("currentUser") CurrentUser me) {
        ListView current = requireOwned(id, me.id());
        String name = request == null || request.name() == null ? current.name() : validName(request.name());
        // 描述允许清空：显式传空串 = 置空；不传 = 保持原值。
        String description = request == null || request.description() == null
                ? current.description() : validDescription(request.description());
        if (!name.equals(current.name()) && lists.nameTaken(me.id(), name, id)) {
            throw ApiException.conflict("a word list with this name already exists");
        }
        try {
            lists.updateMeta(id, me.id(), name, description, clock.instant());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("a word list with this name already exists");
        }
        return requireOwned(id, me.id());
    }

    /** 软删词单：只删容器，**不删任何词条与学习进度**（AC-69.3）。 */
    @DeleteMapping("/{id}")
    public Affected delete(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        requireOwned(id, me.id());
        int affected = lists.softDelete(id, me.id(), clock.instant());
        if (affected == 0) {
            throw ApiException.notFound("word list not found");
        }
        return new Affected(affected);
    }

    @GetMapping("/{id}/words")
    public ListWordPage words(@PathVariable UUID id,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size,
                              @RequestAttribute("currentUser") CurrentUser me) {
        requireOwned(id, me.id());
        if (page < 1) {
            throw ApiException.badRequest("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("size must be 1.." + MAX_PAGE_SIZE);
        }
        return lists.wordsPage(id, page, size);
    }

    @GetMapping("/{id}/review-words")
    public ReviewScope reviewWords(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        requireOwned(id, me.id());
        List<UUID> wordIds = lists.reviewableWordIds(id);
        ListSize size = lists.size(id);
        return new ReviewScope(wordIds, wordIds.size(), size.total(), size.paused(), size.deleted(),
                SESSION_WORD_LIMIT);
    }

    /**
     * 加词：重复加入不报错（返回 duplicated），不属于本人的词 404；`forms` 形式的词不在本人
     * 词库时先入库再引用，软删过的词恢复原词条（进度保留），不新建、不重置进度。
     */
    @PostMapping("/{id}/words")
    @Transactional
    public AddWordsResult addWords(@PathVariable UUID id, @RequestBody(required = false) AddWordsRequest request,
                                   @RequestAttribute("currentUser") CurrentUser me) {
        requireOwned(id, me.id());
        List<UUID> requestedIds = request == null || request.wordIds() == null
                ? List.of() : request.wordIds();
        List<String> forms = request == null || request.forms() == null ? List.of() : request.forms();
        if (requestedIds.isEmpty() && forms.isEmpty()) {
            throw ApiException.badRequest("wordIds or forms is required");
        }
        if (requestedIds.size() > MAX_ADD_PER_REQUEST || forms.size() > MAX_ADD_PER_REQUEST) {
            throw ApiException.badRequest("at most " + MAX_ADD_PER_REQUEST + " words per request");
        }
        String language = request.language() == null || request.language().isBlank()
                ? DEFAULT_LANGUAGE : request.language().strip();

        LinkedHashSet<UUID> resolved = new LinkedHashSet<>();
        for (UUID wordId : requestedIds) {
            if (wordId == null) {
                throw ApiException.badRequest("wordIds must not contain null");
            }
            if (!words.belongsTo(me.id(), wordId)) {
                throw ApiException.notFound("word not found");
            }
            resolved.add(wordId);
        }

        List<UUID> created = new ArrayList<>();
        for (String raw : forms) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.badRequest("blank word form");
            }
            String display = WordNormalizer.display(raw);
            if (!WordNormalizer.valid(display)) {
                throw ApiException.badRequest("invalid word form: " + raw.strip());
            }
            String normalized = WordNormalizer.normalized(display);
            Optional<VocabWordRepository.ExistingWord> existing = words.find(me.id(), language, normalized);
            if (existing.isPresent()) {
                if (existing.get().deleted()) {
                    words.restore(existing.get().id());
                }
                resolved.add(existing.get().id());
            } else {
                WordFamily family = lexicon.lookup(display).orElse(null);
                UUID wordId = words.insert(me.id(), language, display, normalized,
                        family == null ? null : family.familyId(),
                        family == null ? null : family.band());
                created.add(wordId);
                resolved.add(wordId);
            }
        }

        Instant now = clock.instant();
        int position = lists.maxItemPosition(id);
        int added = 0;
        int duplicated = 0;
        for (UUID wordId : resolved) {
            int inserted = lists.insertItem(id, wordId, position + 1, now);
            if (inserted > 0) {
                position++;
                added++;
            } else {
                duplicated++;
            }
        }
        lists.touch(id, now);
        // 与导入同一条路径：新词按 on-import 开关决定是否预热内容（默认关闭，复习时按需入队）。
        generation.enqueueNewWords(created);
        return new AddWordsResult(added, duplicated);
    }

    /** 移出单个词：只删引用，被移出的词仍在词库（AC-69.6；V1 不做批量移出）。 */
    @DeleteMapping("/{id}/words/{wordId}")
    public Affected removeWord(@PathVariable UUID id, @PathVariable UUID wordId,
                                @RequestAttribute("currentUser") CurrentUser me) {
        requireOwned(id, me.id());
        int removed = lists.removeItem(id, wordId);
        if (removed == 0) {
            throw ApiException.notFound("word not in this list");
        }
        lists.touch(id, clock.instant());
        return new Affected(removed);
    }

    private ListView requireOwned(UUID id, UUID userId) {
        return lists.findOwned(id, userId)
                .orElseThrow(() -> ApiException.notFound("word list not found"));
    }

    /** 名字 1–80 字（按 code point 计数，与词形校验同一口径），同一学生内不重名。 */
    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > MAX_NAME) {
            throw ApiException.badRequest("name is required (1-" + MAX_NAME + " chars)");
        }
        return name;
    }

    /** 描述可选，≤200 字；空串表示不写描述（更新时即清空）。 */
    private static String validDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String description = raw.strip();
        if (description.isEmpty()) {
            return null;
        }
        if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION) {
            throw ApiException.badRequest("description must be <=" + MAX_DESCRIPTION + " chars");
        }
        return description;
    }
}
