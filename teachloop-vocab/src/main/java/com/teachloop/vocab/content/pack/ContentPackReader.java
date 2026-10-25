package com.teachloop.vocab.content.pack;

import com.teachloop.vocab.content.ContentReader;
import com.teachloop.vocab.content.StoredContent;
import com.teachloop.vocab.word.WordNormalizer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 只读内容包读取器（decisions/017 P2 / plan §4.1、work/plan-content-tts.md §2）。
 *
 * <p>启动时（构造期）装载 manifest + idx：校验 format / promptVersion / count / 两个 sha256，
 * 把 28 字节/条的 idx 装进内存（10 万词 ≈ 4MB）；pack 原文不常驻，命中时
 * {@link java.nio.channels.FileChannel#read(ByteBuffer, long)} 做一次 positional read
 * （线程安全、不改 channel 位置）。
 *
 * <p>查找：{@code hash64} 二分到相同 hash 的连续区间，逐条 pread + JSON 解析 + 用
 * {@link WordNormalizer#normalized} 复核 word 字段；哈希碰撞时只有词形一致的那条算命中。
 * 记录损坏（读不到、JSON 不合法、缺 word）记 warn 并继续下一条，绝不把异常抛到请求。
 *
 * <p>失败路径（plan §4.1.5）：目录/manifest 缺失与产物损坏一视同仁，由
 * {@code teachloop.vocab.content.pack-required} 决定 —— true 时抛 {@link ContentPackException}
 * 让启动失败（部署了 required=true 却没挂上完整包是配置错误，不能静默退回 AI）；
 * false 时缺失记 info、损坏记 warn，然后降级成只用 delta + AI。
 * {@code verify-sha256=false} 时跳过两个产物的字节比对，但仍校验 manifest 与 idx 结构。
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true", matchIfMissing = true)
public class ContentPackReader implements ContentReader {

    /** 日志/指标里的层名，ContentService 命中后可标 layer=pack。 */
    public static final String LAYER = "pack";

    private static final Logger log = LoggerFactory.getLogger(ContentPackReader.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dir;
    private final boolean required;
    private final Path packDirConfig;

    private ContentPackManifest manifest;
    private ContentPackIndex index;
    private FileChannel pack;
    private String unavailableReason;
    private long loadMillis;

    public ContentPackReader(
            @Value("${teachloop.vocab.content.pack-dir:./fixtures-local/vocab-content}") String packDir,
            @Value("${teachloop.vocab.content.prompt-version:vocab-content-v2.3}") String promptVersion,
            @Value("${teachloop.vocab.content.pack-required:false}") boolean required,
            @Value("${teachloop.vocab.content.verify-sha256:true}") boolean verifySha256) {
        this.dir = Path.of(packDir);
        this.packDirConfig = this.dir.toAbsolutePath().normalize();
        this.required = required;
        load(promptVersion, verifySha256);
    }

    private void load(String expectedPromptVersion, boolean verifySha256) {
        long started = System.nanoTime();
        try {
            if (!Files.isDirectory(dir)) {
                absent("目录不存在：" + packDirConfig);
                return;
            }
            if (!Files.isRegularFile(dir.resolve(ContentPackManifest.FILE_NAME))) {
                absent("目录里没有 manifest.json：" + packDirConfig);
                return;
            }
            ContentPackManifest parsed = ContentPackManifest.read(dir, mapper);
            if (!ContentPackManifest.SUPPORTED_FORMAT.equals(parsed.format())) {
                throw new ContentPackException("manifest format=" + parsed.format()
                        + "，本版本只认 " + ContentPackManifest.SUPPORTED_FORMAT);
            }
            if (expectedPromptVersion != null && !expectedPromptVersion.isBlank()
                    && !expectedPromptVersion.equals(parsed.promptVersion())) {
                throw new ContentPackException("manifest promptVersion=" + parsed.promptVersion()
                        + " 与配置 teachloop.vocab.content.prompt-version=" + expectedPromptVersion
                        + " 不一致（换提示词必须出新包并升配置，否则新旧内容不能混用）");
            }
            Path packPath = dir.resolve(parsed.packFile());
            Path idxPath = dir.resolve(parsed.idxFile());
            if (!Files.isRegularFile(packPath)) {
                throw new ContentPackException("pack 文件缺失：" + packPath);
            }
            if (!Files.isRegularFile(idxPath)) {
                throw new ContentPackException("idx 文件缺失：" + idxPath);
            }
            long packSize = Files.size(packPath);
            if (verifySha256) {
                ContentPackManifest.verifySha256(packPath, parsed.packSha256(), parsed.packFile());
                ContentPackManifest.verifySha256(idxPath, parsed.idxSha256(), parsed.idxFile());
            }
            ContentPackIndex loaded = ContentPackIndex.parse(Files.readAllBytes(idxPath),
                    parsed.count(), packSize);
            FileChannel channel = FileChannel.open(packPath, StandardOpenOption.READ);
            this.manifest = parsed;
            this.index = loaded;
            this.pack = channel;
            this.loadMillis = millisSince(started);
            log.info("内容包已装载 dir={} language={} count={} promptVersion={} pack={}B idx={}B 索引≈{}KB 耗时={}ms",
                    packDirConfig, parsed.language(), parsed.count(), parsed.promptVersion(),
                    packSize, Files.size(idxPath), loaded.heapBytes() / 1024, loadMillis);
        } catch (IOException | RuntimeException ex) {
            closeQuietly();
            String reason = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            if (required) {
                if (ex instanceof ContentPackException contentPackException) {
                    throw contentPackException;
                }
                throw new ContentPackException("内容包装载失败（teachloop.vocab.content.pack-required=true）："
                        + reason, ex);
            }
            degrade(reason, true);
        }
    }

    @Override
    public Optional<StoredContent> get(String language, String normalizedForm) {
        ContentPackManifest manifestNow = manifest;
        ContentPackIndex idx = index;
        if (manifestNow == null || idx == null || normalizedForm == null) {
            return Optional.empty();
        }
        String lang = language == null ? "" : language.strip().toLowerCase(Locale.ROOT);
        if (!lang.equals(manifestNow.language().strip().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        String form = WordNormalizer.normalized(normalizedForm);
        if (form.isEmpty()) {
            return Optional.empty();
        }
        long hash = hash64(manifestNow.language(), form);
        int start = idx.lowerBound(hash);
        for (int i = start; i < idx.count() && idx.hashAt(i) == hash; i++) {
            long id = idx.idAt(i);
            String json = readRecord(idx.offsetAt(i), idx.lengthAt(i));
            if (json == null) {
                continue;
            }
            String word = recordWord(json, id);
            if (word == null || !form.equals(WordNormalizer.normalized(word))) {
                // 哈希碰撞或 idx 与 pack 不配套：这一条不算命中，继续找同 hash 的下一段。
                continue;
            }
            return Optional.of(new StoredContent(id, manifestNow.language(), form,
                    manifestNow.promptVersion(), json, LAYER));
        }
        return Optional.empty();
    }

    @Override
    public Optional<StoredContent> getById(long contentId) {
        ContentPackManifest manifestNow = manifest;
        ContentPackIndex idx = index;
        if (manifestNow == null || idx == null || contentId < 1 || contentId > idx.count()) {
            return Optional.empty();
        }
        long offset = idx.offsetForId(contentId);
        int length = idx.lengthForId(contentId);
        if (offset < 0 || length <= 0) {
            return Optional.empty();
        }
        String json = readRecord(offset, length);
        if (json == null) {
            return Optional.empty();
        }
        String word = recordWord(json, contentId);
        if (word == null) {
            return Optional.empty();
        }
        String form = WordNormalizer.normalized(word);
        if (form.isEmpty()) {
            log.warn("内容包记录 word 规范化后为空，按 miss 处理 id={}", contentId);
            return Optional.empty();
        }
        return Optional.of(new StoredContent(contentId, manifestNow.language(), form,
                manifestNow.promptVersion(), json, LAYER));
    }

    @Override
    public String layer() {
        return LAYER;
    }

    /** 包是否可用（false 时 get/getById 一律 empty，由 delta + AI 兜底）。 */
    public boolean available() {
        return index != null && pack != null;
    }

    public int count() {
        return index == null ? 0 : index.count();
    }

    public String language() {
        return manifest == null ? "" : manifest.language();
    }

    public String promptVersion() {
        return manifest == null ? "" : manifest.promptVersion();
    }

    public String version() {
        return manifest == null ? "" : manifest.version();
    }

    public Path dir() {
        return packDirConfig;
    }

    public boolean packRequired() {
        return required;
    }

    public String unavailableReason() {
        return unavailableReason == null ? "" : unavailableReason;
    }

    public long loadMillis() {
        return loadMillis;
    }

    /** 索引常驻内存估算（字节）；10 万词 ≈ 4.0MB。 */
    public long indexHeapBytes() {
        return index == null ? 0 : index.heapBytes();
    }

    @PreDestroy
    public void close() {
        closeQuietly();
    }

    /**
     * hash64 = sha256(UTF-8(language + "|" + normalizedForm)) 的前 8 字节，按大端解释为无符号 long。
     * 与导入器 {@code scripts/vocab/build_content_pack.py} 的 {@code hash64()} 必须逐字节一致。
     */
    static long hash64(String language, String normalizedForm) {
        MessageDigest digest = ContentPackManifest.newDigest();
        byte[] bytes = digest.digest((language + "|" + normalizedForm).getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(bytes, 0, Long.BYTES).getLong();
    }

    private String readRecord(long offset, int length) {
        FileChannel channel = pack;
        if (channel == null || length <= 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = offset;
        try {
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position);
                if (read < 0) {
                    log.warn("内容包记录被截断 offset={} length={}（idx 与 pack 不配套），按 miss 处理",
                            offset, length);
                    return null;
                }
                position += read;
            }
        } catch (IOException ex) {
            log.warn("内容包读取失败 offset={} length={}，按 miss 处理：{}", offset, length, ex.toString());
            return null;
        }
        return new String(buffer.array(), StandardCharsets.UTF_8);
    }

    private String recordWord(String json, long contentId) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                log.warn("内容包记录不是 JSON 对象 id={}，按 miss 处理", contentId);
                return null;
            }
            JsonNode word = root.get("word");
            if (word == null || !word.isString() || word.asString().isBlank()) {
                log.warn("内容包记录缺少 word 字段 id={}，按 miss 处理", contentId);
                return null;
            }
            return word.asString();
        } catch (RuntimeException ex) {
            log.warn("内容包记录 JSON 解析失败 id={}，按 miss 处理：{}", contentId, ex.toString());
            return null;
        }
    }

    /** 缺失（目录 / manifest）：required=true 启动失败，false 记 info 后降级。 */
    private void absent(String reason) {
        if (required) {
            throw new ContentPackException("内容包装载失败（teachloop.vocab.content.pack-required=true）："
                    + reason);
        }
        degrade(reason, false);
    }

    private void degrade(String reason, boolean warning) {
        this.unavailableReason = reason == null ? "" : reason;
        this.manifest = null;
        this.index = null;
        if (warning) {
            log.warn("内容包不可用，全局内容只用 delta + AI：{}", reason);
        } else {
            log.info("内容包未装载（{}），全局内容只用 delta + AI", reason);
        }
    }

    private void closeQuietly() {
        FileChannel channel = pack;
        this.pack = null;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ex) {
                log.debug("关闭内容包 channel 失败：{}", ex.toString());
            }
        }
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

}
