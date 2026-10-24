package com.teachloop.vocab.importer;

import java.util.List;

/**
 * 一次文本解析的结果（T390 / FR-108）。
 *
 * <p>{@code tokens} 保留原始词形与行号；预览页的去重计数用 {@link #distinctNormalized()}。
 * {@code skipped} 是 markdown 标题/引用、表格表头与分隔行、说明文字等**被跳过**的行，
 * 每条都带行号与原文——宪法口径：静默丢弃即缺陷。
 *
 * <p>{@code pending} 只服务于「学习语言 = zh」的反向规则（spec §15.2）：含纯 ASCII 且无中文的
 * 短行可能是外语说明，解析器不替用户决定，交给预览页确认。
 */
public record ParseResult(
        int lineCount,
        List<ParsedToken> tokens,
        List<SkippedLine> skipped,
        List<PendingLine> pending) {

    public ParseResult {
        tokens = List.copyOf(tokens);
        skipped = List.copyOf(skipped);
        pending = List.copyOf(pending);
    }

    public record ParsedToken(String raw, int lineNumber) {
    }

    public record SkippedLine(int lineNumber, String text, String reason) {
    }

    public record PendingLine(int lineNumber, String text, String reason) {
    }

    /** 按「去首尾空格 + 小写」去重后的词形，保持首次出现顺序（完整规范化在 T393）。 */
    public List<String> distinctNormalized() {
        return tokens.stream()
                .map(token -> token.raw().strip().toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
    }
}
