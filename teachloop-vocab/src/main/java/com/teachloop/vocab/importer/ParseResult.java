package com.teachloop.vocab.importer;

import java.util.List;

/**
 * 一次文本解析的结果（T390 / FR-108）。
 *
 * <p>{@code tokens} 保留原始词形与行号；预览页的去重计数用 {@link #distinctNormalized()}。
 * {@code skipped} 是 markdown 标题/引用、表格表头与分隔行、说明文字等**被跳过**的行，
 * 每条都带行号与原文——宪法口径：静默丢弃即缺陷。
 *
 * <p>{@code cleanups} 是 T391 的格式清洗痕迹：每类改动一行一条，带行号、规则与前后值，
 * 预览页据此逐条展示（spec §20「格式清洗自动执行且可追溯」）。
 * {@code pending} 只服务于「学习语言 = zh」的反向规则（spec §15.2）。
 */
public record ParseResult(
        int lineCount,
        List<ParsedToken> tokens,
        List<SkippedLine> skipped,
        List<PendingLine> pending,
        List<Cleanup> cleanups) {

    public ParseResult {
        tokens = List.copyOf(tokens);
        skipped = List.copyOf(skipped);
        pending = List.copyOf(pending);
        cleanups = List.copyOf(cleanups);
    }

    public record ParsedToken(String raw, int lineNumber) {
    }

    public record SkippedLine(int lineNumber, String text, String reason) {
    }

    public record PendingLine(int lineNumber, String text, String reason) {
    }

    /** 一条可追溯的格式清洗记录：第 N 行按某规则把 before 改成 after（after 为空表示整词丢弃）。 */
    public record Cleanup(int lineNumber, String rule, String before, String after) {
    }

    /** 按「去首尾空格 + 小写」去重后的词形，保持首次出现顺序（完整规范化在 T393）。 */
    public List<String> distinctNormalized() {
        return tokens.stream()
                .map(token -> token.raw().strip().toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
    }
}
