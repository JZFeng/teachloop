package com.teachloop.vocab.content.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 只读内容包的 {@code manifest.json}（decisions/017 P2 / plan §4.1.1、work/plan-content-tts.md §2）。
 *
 * <p>manifest 是「包已完整」的提交点：导入器最后写它，读取器先校验它。校验口径：
 * format 必须等于 {@link #SUPPORTED_FORMAT}；language / promptVersion 非空；count 是 0..5,000,000
 * 的整数；packFile / idxFile 只能是纯文件名（不允许目录穿越）；两个 sha256 是 64 位小写十六进制，
 * 且与文件实际字节一致（{@code verify-sha256=false} 时可跳过字节比对，但仍要求字段格式合法）。
 *
 * <p>本类只负责解析与校验字段，不打开 pack/idx；索引布局见 {@link ContentPackIndex}。
 */
public record ContentPackManifest(String format, String language, String version, String promptVersion,
                                  int count, String packFile, String idxFile,
                                  String packSha256, String idxSha256, String createdAt) {

    /** 只认这一代格式；换布局 = 换 format 字符串，旧读取器拒绝装载。 */
    public static final String SUPPORTED_FORMAT = "teachloop-content-pack-v1";

    /** manifest 文件名固定，不做配置。 */
    static final String FILE_NAME = "manifest.json";

    /** 10 万词是目标量级；5,000,000 只是防止 manifest 写错时分配超大数组。 */
    static final int MAX_COUNT = 5_000_000;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PLAIN_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    static ContentPackManifest read(Path dir, ObjectMapper mapper) throws IOException {
        Path path = dir.resolve(FILE_NAME);
        JsonNode root;
        try {
            root = mapper.readTree(path);
        } catch (RuntimeException ex) {
            throw new ContentPackException(path + " 解析失败：" + ex.getMessage(), ex);
        }
        if (root == null || !root.isObject()) {
            throw new ContentPackException(path + " 不是 JSON 对象");
        }
        String format = text(root, "format");
        String language = text(root, "language");
        String version = text(root, "version");
        String promptVersion = text(root, "promptVersion");
        int count = count(root);
        String packFile = plainFileName(root, "packFile");
        String idxFile = plainFileName(root, "idxFile");
        String packSha256 = sha256(root, "packSha256");
        String idxSha256 = sha256(root, "idxSha256");
        String createdAt = optionalText(root, "createdAt");
        return new ContentPackManifest(format, language, version, promptVersion, count,
                packFile, idxFile, packSha256, idxSha256, createdAt);
    }

    /** 校验一个产物文件的字节与 manifest 记录的 sha256 一致。 */
    static void verifySha256(Path file, String expected, String label) throws IOException {
        String actual = sha256(file);
        if (!actual.equals(expected)) {
            throw new ContentPackException(label + " sha256 " + actual
                    + " 与 manifest " + expected + " 不一致（包被改动或与 manifest 不配套）");
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new ContentPackException("manifest 缺少字段 " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new ContentPackException("manifest 字段 " + field + " 必须是非空字符串");
        }
        return value.asString();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : "";
    }

    private static int count(JsonNode node) {
        JsonNode value = required(node, "count");
        if (!value.isNumber()) {
            throw new ContentPackException("manifest 字段 count 必须是数字");
        }
        long count = value.asLong();
        if (count < 0 || count > MAX_COUNT) {
            throw new ContentPackException("manifest count=" + count
                    + " 超出 0.." + MAX_COUNT + "（条数与包规模对不上）");
        }
        return (int) count;
    }

    private static String plainFileName(JsonNode node, String field) {
        String name = text(node, field);
        if (!PLAIN_FILE_NAME.matcher(name).matches()) {
            throw new ContentPackException("manifest 字段 " + field + " 必须是纯文件名（不允许路径）：" + name);
        }
        return name;
    }

    private static String sha256(JsonNode node, String field) {
        String value = text(node, field);
        if (!SHA256.matcher(value).matches()) {
            throw new ContentPackException("manifest 字段 " + field + " 不是 64 位小写十六进制：" + value);
        }
        return value;
    }
}
