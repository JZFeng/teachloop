package com.teachloop.vocab.importer;

import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.spelling.SpellingChecker;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>开关关闭时整条路由不存在（T389 / plan §11）；未登录由安全配置统一 401。
 */
@RestController
@RequestMapping("/api/vocab/import")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ImportPreviewEndpoint {

    private final DictionaryTextParser parser;
    private final SpellingChecker spelling;
    private final WordImportService importService;

    public ImportPreviewEndpoint(DictionaryTextParser parser, SpellingChecker spelling,
                                 WordImportService importService) {
        this.parser = parser;
        this.spelling = spelling;
        this.importService = importService;
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
    public WordImportService.CommitResult commit(@RequestBody(required = false) ImportTextRequest request,
                                                 @RequestAttribute("currentUser") CurrentUser me) {
        String text = request == null ? "" : request.text();
        String language = request == null || request.language() == null || request.language().isBlank()
                ? "en" : request.language();
        return importService.commit(me.id(), language, text, request == null ? null : request.label());
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
