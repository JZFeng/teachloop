package com.teachloop.vocab.lexicon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 读取 `lexicon-families.tsv` / `lexicon-forms.tsv` / `manifest.json`（T388）。
 *
 * <p>先按 manifest 校验两个产物的字节数与 sha256，再解析；计数与 manifest 对不上、
 * 列数不对、词形重复、family_id 悬空都直接报错（由 {@link LexiconBootstrap} 降级），
 * 避免把半份索引装进内存。
 */
@Component
public class LexiconReader {

    static final String SUPPORTED_FORMAT = "teachloop-lexicon-v1";
    private static final int FAMILY_COLUMNS = 4;
    private static final int FORM_COLUMNS = 5;

    private final ObjectMapper mapper = new ObjectMapper();

    public LexiconData read(Path dir) throws IOException {
        Path manifestPath = dir.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw new LexiconException("找不到 manifest：" + manifestPath);
        }
        LexiconManifest manifest = manifestOf(mapper.readTree(manifestPath));
        if (!SUPPORTED_FORMAT.equals(manifest.format())) {
            throw new LexiconException("manifest format=" + manifest.format()
                    + "，本版本只认 " + SUPPORTED_FORMAT);
        }
        verifyArtifact(dir, manifest.families());
        verifyArtifact(dir, manifest.forms());

        List<FamilyRow> families = readFamilies(dir.resolve(manifest.families().file()));
        List<FormRow> forms = readForms(dir.resolve(manifest.forms().file()));

        if (families.size() != manifest.counts().families()) {
            throw new LexiconException("族数 " + families.size()
                    + " 与 manifest " + manifest.counts().families() + " 不一致");
        }
        if (forms.size() != manifest.counts().forms()) {
            throw new LexiconException("形数 " + forms.size()
                    + " 与 manifest " + manifest.counts().forms() + " 不一致");
        }
        Set<Long> familyIds = new HashSet<>(families.size() * 2);
        for (FamilyRow family : families) {
            if (!familyIds.add(family.id())) {
                throw new LexiconException("族 id 重复：" + family.id());
            }
        }
        for (FormRow form : forms) {
            if (!familyIds.contains(form.familyId())) {
                throw new LexiconException("词形 " + form.normalizedForm()
                        + " 的 family_id=" + form.familyId() + " 在族表里不存在");
            }
        }
        return new LexiconData(manifest, families, forms);
    }

    private static LexiconManifest manifestOf(JsonNode root) {
        String format = text(root, "format");
        String contentSha256 = text(root, "content_sha256");
        if (!contentSha256.matches("[0-9a-f]{64}")) {
            throw new LexiconException("manifest content_sha256 不是 64 位十六进制：" + contentSha256);
        }
        JsonNode counts = required(root, "counts");
        JsonNode artifacts = required(root, "artifacts");
        List<LexiconManifest.SourceFile> sources = new ArrayList<>();
        JsonNode files = root.path("source").path("files");
        if (files.isArray()) {
            for (JsonNode file : files.values()) {
                sources.add(new LexiconManifest.SourceFile(text(file, "name"), text(file, "sha256")));
            }
        }
        return new LexiconManifest(
                format,
                contentSha256,
                new LexiconManifest.Counts(number(counts, "families"), number(counts, "forms")),
                artifact(artifacts.path("families"), "families"),
                artifact(artifacts.path("forms"), "forms"),
                List.copyOf(sources));
    }

    private static LexiconManifest.Artifact artifact(JsonNode node, String what) {
        String file = text(node, "file");
        String sha256 = text(node, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new LexiconException("manifest " + what + ".sha256 不是 64 位十六进制：" + sha256);
        }
        JsonNode bytes = required(node, "bytes");
        JsonNode rows = required(node, "rows");
        return new LexiconManifest.Artifact(file, sha256, bytes.asLong(), rows.asInt());
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new LexiconException("manifest 缺少字段 " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new LexiconException("manifest 字段 " + field + " 必须是非空字符串");
        }
        return value.asString();
    }

    private static int number(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isNumber()) {
            throw new LexiconException("manifest 字段 " + field + " 必须是数字");
        }
        return value.asInt();
    }

    private static void verifyArtifact(Path dir, LexiconManifest.Artifact artifact) throws IOException {
        Path path = dir.resolve(artifact.file());
        if (!Files.isRegularFile(path)) {
            throw new LexiconException("产物缺失：" + path);
        }
        long size = Files.size(path);
        if (size != artifact.bytes()) {
            throw new LexiconException(path.getFileName() + " 字节数 " + size
                    + " 与 manifest " + artifact.bytes() + " 不一致");
        }
        String actual = sha256(path);
        if (!actual.equals(artifact.sha256())) {
            throw new LexiconException(path.getFileName() + " sha256 " + actual
                    + " 与 manifest " + artifact.sha256() + " 不一致（产物被改动或版本不配套）");
        }
    }

    private static List<FamilyRow> readFamilies(Path path) throws IOException {
        List<FamilyRow> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                String[] cells = line.split("\t", -1);
                if (cells.length != FAMILY_COLUMNS) {
                    throw new LexiconException(path.getFileName() + ":" + lineNumber
                            + " 列数 " + cells.length + "，期望 " + FAMILY_COLUMNS);
                }
                try {
                    rows.add(new FamilyRow(Long.parseLong(cells[0]), cells[1],
                            Integer.parseInt(cells[2]), Long.parseLong(cells[3])));
                } catch (NumberFormatException ex) {
                    throw new LexiconException(path.getFileName() + ":" + lineNumber
                            + " 数字字段不合法：" + ex.getMessage());
                }
            }
        }
        return rows;
    }

    private static List<FormRow> readForms(Path path) throws IOException {
        List<FormRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                String[] cells = line.split("\t", -1);
                if (cells.length != FORM_COLUMNS) {
                    throw new LexiconException(path.getFileName() + ":" + lineNumber
                            + " 列数 " + cells.length + "，期望 " + FORM_COLUMNS);
                }
                if (!seen.add(cells[0])) {
                    throw new LexiconException(path.getFileName() + ":" + lineNumber
                            + " 词形重复：" + cells[0]);
                }
                boolean head = switch (cells[4]) {
                    case "1" -> true;
                    case "0" -> false;
                    default -> throw new LexiconException(path.getFileName() + ":" + lineNumber
                            + " is_head 只能是 0/1：" + cells[4]);
                };
                try {
                    rows.add(new FormRow(cells[0], cells[1], Long.parseLong(cells[2]),
                            Long.parseLong(cells[3]), head));
                } catch (NumberFormatException ex) {
                    throw new LexiconException(path.getFileName() + ":" + lineNumber
                            + " 数字字段不合法：" + ex.getMessage());
                }
            }
        }
        return rows;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
