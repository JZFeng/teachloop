package com.teachloop.vocab.lexicon;

import java.util.List;

/**
 * 构建脚本产出的 manifest（`manifest.json`）里本模块用到的字段。
 *
 * <p>{@code contentSha256} 覆盖源文件 sha256、两个产物 sha256 与计数，不含构建时间；
 * 它变了才重建数据库索引（plan §3.1）。
 */
public record LexiconManifest(
        String format,
        String contentSha256,
        Counts counts,
        Artifact families,
        Artifact forms,
        List<SourceFile> sourceFiles) {

    public record Counts(int families, int forms) {
    }

    public record Artifact(String file, String sha256, long bytes, int rows) {
    }

    public record SourceFile(String name, String sha256) {
    }
}
