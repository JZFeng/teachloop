package com.teachloop.vocab.importer;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导入/粘贴的预览接口（T391 / plan §7）。
 *
 * <p>只做「文本 → 解析 + 清洗痕迹」：返回总行数、token、去重词形、跳过行与每一条清洗记录。
 * 预计「新建 / 重复 / 失败」与列映射要查库，由 T393 在这条管线上补；文件上传也在 T393。
 *
 * <p>开关关闭时整条路由不存在（T389 / plan §11），前端也不会显示入口。
 */
@RestController
@RequestMapping("/api/vocab/import")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ImportPreviewEndpoint {

    private final DictionaryTextParser parser;

    public ImportPreviewEndpoint(DictionaryTextParser parser) {
        this.parser = parser;
    }

    @PostMapping("/preview")
    public PreviewResponse preview(@RequestBody(required = false) PreviewRequest request) {
        ParseResult result = parser.parse(
                request == null ? "" : request.text(),
                request == null ? null : request.language());
        return new PreviewResponse(
                result.lineCount(),
                result.tokens().stream()
                        .map(token -> new TokenView(token.raw(), token.lineNumber()))
                        .toList(),
                result.distinctNormalized(),
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

    public record PreviewRequest(String text, String language) {
    }

    public record PreviewResponse(
            int lineCount,
            List<TokenView> tokens,
            List<String> distinctWords,
            List<SkippedView> skipped,
            List<PendingView> pending,
            List<CleanupView> cleanups) {
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
}
