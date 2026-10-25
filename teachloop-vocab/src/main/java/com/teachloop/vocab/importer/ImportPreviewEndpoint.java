package com.teachloop.vocab.importer;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.lists.VocabularyListRepository;
import com.teachloop.vocab.spelling.SpellingChecker;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导入/粘贴的预览与提交接口（T391 / T393 / plan §7）。
 *
 * <p>预览返回：行数、token、去重词形、拼写三态、预计「新建 / 重复 / 失败」、行号级错误、
 * 跳过行与每一条清洗记录。提交走 {@link WordImportService}，与 `/api/vocab/words/paste`
 * 是同一条管线（FR-109）。
 *
 * <p>T453 / AC-69.5：提交时可带 {@code listName}，把这一批词一并加入同名词单（不存在则创建）。
 * 达词单上限时**导入照常完成**，只在响应的 {@code list.skippedReason} 里说明该批未进词单。
 *
 * <p>开关关闭时整条路由不存在（T389 / plan §11）；未登录由安全配置统一 401。
 */
@RestController
@RequestMapping("/api/vocab/import")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ImportPreviewEndpoint {

    private static final int MAX_LIST_NAME = 80;

    private final DictionaryTextParser parser;
    private final SpellingChecker spelling;
    private final WordImportService importService;
    private final VocabularyListRepository lists;
    private final Clock clock;
    private final int maxListsPerStudent;

    public ImportPreviewEndpoint(DictionaryTextParser parser, SpellingChecker spelling,
                                 WordImportService importService, VocabularyListRepository lists,
                                 Clock clock,
                                 @Value("${teachloop.vocab.lists.max-per-student:100}")
                                 int maxListsPerStudent) {
        this.parser = parser;
        this.spelling = spelling;
        this.importService = importService;
        this.lists = lists;
        this.clock = clock;
        this.maxListsPerStudent = Math.max(1, maxListsPerStudent);
    }

    @PostMapping("/preview")
    public PreviewResponse preview(@RequestBody(required = false) ImportTextRequest request,
                                   @RequestAttribute("currentUser") CurrentUser me) {
        String text = request == null ? "" : request.text();
        String language = request == null ? null : request.language();
        String resolvedLanguage = language == null || language.isBlank() ? "en" : language;
        ParseResult result = parser.parse(text, language);
        WordImportService.PreviewResult preview = importService.preview(me.id(), resolvedLanguage, text);
        List<String> distinct = result.distinctNormalized();
        boolean spellingChecked = spelling.available();
        List<SpellingView> spellingViews = spellingChecked
                ? distinct.stream().map(word -> {
                    SpellingChecker.SpellingResult check = spelling.check(word);
                    return new SpellingView(
                            check.word(),
                            check.state(),
                            check.band(),
                            check.candidates().stream()
                                    .map(candidate -> new CandidateView(
                                            candidate.form(), candidate.distance(), candidate.band()))
                                    .toList());
                }).toList()
                : List.of();
        return new PreviewResponse(
                result.lineCount(),
                result.tokens().stream()
                        .map(token -> new TokenView(token.raw(), token.lineNumber()))
                        .toList(),
                distinct,
                spellingChecked,
                spellingViews,
                new CountsView(preview.counts().total(), preview.counts().created(),
                        preview.counts().duplicated(), preview.counts().failed()),
                preview.errors().stream()
                        .map(error -> new LineErrorView(error.line(), error.raw(), error.reason()))
                        .toList(),
                result.skipped().stream()
                        .map(line -> new SkippedView(line.lineNumber(), line.text(), line.reason()))
                        .toList(),
                result.pending().stream()
                        .map(line -> new PendingView(line.lineNumber(), line.text(), line.reason()))
                        .toList(),
                result.cleanups().stream()
                        .map(line -> new CleanupView(
                                line.lineNumber(), line.rule(), line.before(), line.after()))
                        .toList());
    }

    @PostMapping("/commit")
    @Transactional
    public CommitResponse commit(@RequestBody(required = false) ImportTextRequest request,
                                 @RequestAttribute("currentUser") CurrentUser me) {
        String text = request == null ? "" : request.text();
        String language = request == null || request.language() == null || request.language().isBlank()
                ? "en" : request.language();
        String rawListName = request == null ? null : request.listName();
        String listName = rawListName == null || rawListName.isBlank() ? null : rawListName.strip();
        if (listName != null && listName.length() > MAX_LIST_NAME) {
            throw ApiException.badRequest("listName is longer than " + MAX_LIST_NAME);
        }
        WordImportService.CommitResult result =
                importService.commit(me.id(), language, text, request == null ? null : request.label());
        CommitAttachment attachment = listName == null ? null
                : attachToOwnList(me.id(), listName, result.batchId(), clock.instant());
        return new CommitResponse(result.counts(), result.batchId(), result.errors(), attachment);
    }

    /**
     * 把这一批词挂到同名词单（AC-69.5）：已有则复用，没有则创建；达上限时不建单、
     * 也不回滚导入，只把原因放进 {@code skippedReason} 让界面提示「该批未进词单」。
     */
    private CommitAttachment attachToOwnList(UUID userId, String name, UUID batchId, Instant now) {
        Optional<UUID> existing = lists.findActiveIdByName(userId, name);
        UUID listId;
        boolean created = false;
        if (existing.isPresent()) {
            listId = existing.get();
        } else {
            if (lists.countActive(userId) >= maxListsPerStudent) {
                return new CommitAttachment(name, null, false, 0, 0, "list-limit");
            }
            listId = UUID.randomUUID();
            try {
                lists.create(listId, userId, name, null, lists.nextPosition(userId), now);
                created = true;
            } catch (DuplicateKeyException concurrent) {
                // 并发提交同名：谁先建谁赢，这里改用已存在的那份，不把导入打成失败。
                listId = lists.findActiveIdByName(userId, name)
                        .orElseThrow(() -> concurrent);
            }
        }
        int position = lists.maxItemPosition(listId);
        int added = 0;
        int duplicated = 0;
        for (UUID wordId : lists.wordIdsByBatch(userId, batchId)) {
            if (lists.insertItem(listId, wordId, ++position, now) > 0) {
                added++;
            } else {
                duplicated++;
            }
        }
        if (added > 0) {
            lists.touch(listId, now);
        }
        return new CommitAttachment(name, listId, created, added, duplicated, null);
    }

    /** 提交结果：原三个字段保持不变，多一个 {@code list}（未指定词单名时为 null）。 */
    public record CommitResponse(WordImportService.Counts counts, UUID batchId,
                                 List<WordImportService.LineError> errors, CommitAttachment list) {
    }

    /** 词单附挂结果；{@code skippedReason} 非空表示导入完成、但该批没进词单。 */
    public record CommitAttachment(String name, UUID listId, boolean created, int added,
                                   int duplicated, String skippedReason) {
    }

    public record PreviewResponse(
            int lineCount,
            List<TokenView> tokens,
            List<String> distinctWords,
            boolean spellingChecked,
            List<SpellingView> spelling,
            CountsView counts,
            List<LineErrorView> lineErrors,
            List<SkippedView> skipped,
            List<PendingView> pending,
            List<CleanupView> cleanups) {
    }

    /** 预计「新建 / 重复跳过 / 会失败」，三项之和等于 token 总数。 */
    public record CountsView(int total, int created, int duplicated, int failed) {
    }

    public record LineErrorView(int line, String raw, String reason) {
    }

    public record TokenView(String raw, int line) {
    }

    public record SkippedView(int line, String text, String reason) {
    }

    public record PendingView(int line, String text, String reason) {
    }

    /** 第 line 行按 rule 把 before 改成 after；after 为空表示整词丢弃。 */
    public record CleanupView(int line, String rule, String before, String after) {
    }

    /** 三态：OK 词表命中（band 有值）；SUSPECT 给候选；UNKNOWN 词表外。 */
    public record SpellingView(String word, SpellingChecker.State state, Integer band,
                               List<CandidateView> candidates) {
    }

    public record CandidateView(String form, int distance, int band) {
    }
}
