package com.teachloop.vocab.content.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teachloop.vocab.content.StoredContent;
import com.teachloop.vocab.word.WordNormalizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T430：只读内容包读取器。
 *
 * <p>测试自己按 {@code work/plan-content-tts.md} §2 的格式写 pack/idx/manifest（28 字节大端 idx、
 * unsigned hash64 升序、hash64 = sha256("language|normalizedForm") 前 8 字节），覆盖：正常命中、
 * 大小写/全角/变音符、按 id、哈希碰撞复核、损坏记录降级、manifest/idx 损坏拒绝、缺失降级、
 * 提示词版本不一致、10 万词装载预算，以及（产物存在时）真实导入包的互操作。
 */
class ContentPackReaderTest {

    private static final String PROMPT_VERSION = "vocab-content-v2.3";
    private static final String PACK_FILE = "content-en-test.pack";
    private static final String IDX_FILE = "content-en-test.idx";

    @TempDir
    Path tempDir;

    private int fixtures;

    @Test
    @DisplayName("按规范化词形命中；大小写/全角/变音符/空格都能命中；语言不符与未收录返回 empty")
    void hitsByNormalizedForm() throws IOException {
        Path dir = pack()
                .add("apple", "{\"word\":\"apple\",\"v\":1}")
                .add("Banana", "{\"word\":\"Banana\",\"v\":2}")
                .add("café", "{\"word\":\"café\",\"v\":3}")
                .write();
        ContentPackReader reader = reader(dir);

        assertThat(reader.available()).isTrue();
        assertThat(reader.count()).isEqualTo(3);
        assertThat(reader.language()).isEqualTo("en");
        assertThat(reader.promptVersion()).isEqualTo(PROMPT_VERSION);
        assertThat(reader.layer()).isEqualTo("pack");

        assertThat(reader.get("en", "apple")).get().satisfies(content -> {
            assertThat(content.id()).isEqualTo(1L);
            assertThat(content.language()).isEqualTo("en");
            assertThat(content.normalizedForm()).isEqualTo("apple");
            assertThat(content.promptVersion()).isEqualTo(PROMPT_VERSION);
            assertThat(content.source()).isEqualTo("pack");
            assertThat(content.json()).contains("\"v\":1");
        });
        // 大小写 / 全角 / 变音符 / 首尾空格：读取器内部再规范化一次，与 WordNormalizer 同口径
        assertThat(reader.get("en", WordNormalizer.normalized("  ＡＰＰＬＥ "))).get()
                .extracting(StoredContent::id).isEqualTo(1L);
        assertThat(reader.get("en", "CAFÉ")).get()
                .extracting(StoredContent::normalizedForm).isEqualTo("cafe");
        assertThat(reader.get("en", "banana")).get()
                .extracting(StoredContent::id).isEqualTo(2L);
        // 语言、拼写、空词形都不命中
        assertThat(reader.get("fr", "apple")).isEmpty();
        assertThat(reader.get("en", "pear")).isEmpty();
        assertThat(reader.get("en", "   ")).isEmpty();
        assertThat(reader.get("en", null)).isEmpty();
    }

    @Test
    @DisplayName("getById：1..N 直接 pread，越界/非法 id 返回 empty")
    void getByIdReturnsRecord() throws IOException {
        Path dir = pack()
                .add("apple", "{\"word\":\"apple\",\"v\":1}")
                .add("Banana", "{\"word\":\"Banana\",\"v\":2}")
                .add("café", "{\"word\":\"café\",\"v\":3}")
                .write();
        ContentPackReader reader = reader(dir);

        assertThat(reader.getById(2)).get().satisfies(content -> {
            assertThat(content.id()).isEqualTo(2L);
            assertThat(content.normalizedForm()).isEqualTo("banana");
            assertThat(content.json()).contains("\"v\":2");
            assertThat(content.source()).isEqualTo("pack");
        });
        assertThat(reader.getById(3)).get()
                .extracting(StoredContent::normalizedForm).isEqualTo("cafe");
        assertThat(reader.getById(0)).isEmpty();
        assertThat(reader.getById(-1)).isEmpty();
        assertThat(reader.getById(4)).isEmpty();
    }

    @Test
    @DisplayName("hash64 相同的记录逐个复核 word：碰撞不会把 A 的内容当成 B")
    void hashCollisionDoesNotFalseHit() throws IOException {
        // 人为让两条记录落在同一个 hash 桶里（真实 sha256 碰撞不可构造，格式允许同 hash 多条）。
        long collision = hashOf("en", WordNormalizer.normalized("apricot"));
        Path dir = pack()
                .addWithHash(collision, "{\"word\":\"apple\",\"v\":\"apple\"}")
                .addWithHash(collision, "{\"word\":\"apricot\",\"v\":\"apricot\"}")
                .write();
        ContentPackReader reader = reader(dir);

        assertThat(reader.get("en", "apricot")).get().satisfies(content -> {
            assertThat(content.id()).isEqualTo(2L);
            assertThat(content.json()).contains("apricot");
        });
        // apple 的真实 hash 不在索引里（它的记录被放进了 apricot 的桶）：不能误命中
        assertThat(reader.get("en", "apple")).isEmpty();
    }

    @Test
    @DisplayName("记录损坏（坏 JSON / word 不符 / 被截断）按 miss 处理，同 hash 的后续记录仍能命中")
    void corruptRecordFallsBackToMiss() throws IOException {
        long appleHash = hashOf("en", WordNormalizer.normalized("apple"));
        Path dir = pack()
                .addWithHash(appleHash, "{not json")
                .addWithHash(appleHash, "{\"word\":\"apricot\"}")
                .add("apple", "{\"word\":\"apple\",\"v\":\"good\"}")
                .write();
        ContentPackReader reader = reader(dir);

        assertThat(reader.get("en", "apple")).get().satisfies(content -> {
            assertThat(content.id()).isEqualTo(3L);
            assertThat(content.json()).contains("good");
        });
        assertThat(reader.getById(1)).isEmpty();
        // id=2 是合法记录（word=apricot），按 id 读不检查查询词形，所以仍返回它自己的词形
        assertThat(reader.getById(2)).get()
                .extracting(StoredContent::normalizedForm).isEqualTo("apricot");
        // 损坏记录不影响读取器本身
        assertThat(reader.available()).isTrue();
    }

    @Test
    @DisplayName("pack-dir 不存在：required=false 记 info 降级，required=true 启动失败")
    void missingPackDirFollowsRequiredFlag() {
        String absent = tempDir.resolve("not-here").toString();
        ContentPackReader degraded = new ContentPackReader(absent, PROMPT_VERSION, false, true);
        assertThat(degraded.available()).isFalse();
        assertThat(degraded.unavailableReason()).contains("目录不存在");
        assertThat(degraded.get("en", "apple")).isEmpty();
        assertThat(degraded.getById(1)).isEmpty();
        assertThat(degraded.count()).isZero();

        assertThatThrownBy(() -> new ContentPackReader(absent, PROMPT_VERSION, true, true))
                .isInstanceOf(ContentPackException.class)
                .hasMessageContaining("目录不存在")
                .hasMessageContaining("pack-required=true");
    }

    @Test
    @DisplayName("空目录 / 缺 manifest.json：required=false 降级，required=true 启动失败")
    void missingManifestFollowsRequiredFlag() throws IOException {
        Path emptyDir = Files.createDirectory(tempDir.resolve("empty-pack-dir"));
        ContentPackReader degradedEmpty = new ContentPackReader(emptyDir.toString(), PROMPT_VERSION, false, true);
        assertThat(degradedEmpty.available()).isFalse();
        assertThat(degradedEmpty.unavailableReason()).contains("manifest.json");
        assertThatThrownBy(() -> new ContentPackReader(emptyDir.toString(), PROMPT_VERSION, true, true))
                .isInstanceOf(ContentPackException.class)
                .hasMessageContaining("manifest.json");

        Path noManifest = pack().add("apple", "{\"word\":\"apple\"}").withoutManifest().write();
        ContentPackReader degraded = new ContentPackReader(noManifest.toString(), PROMPT_VERSION, false, true);
        assertThat(degraded.available()).isFalse();
        assertThat(degraded.get("en", "apple")).isEmpty();
        assertThatThrownBy(() -> new ContentPackReader(noManifest.toString(), PROMPT_VERSION, true, true))
                .isInstanceOf(ContentPackException.class)
                .hasMessageContaining("manifest.json");
    }

    @Test
    @DisplayName("manifest 损坏：required=true 启动失败，false 降级；JSON/format/count/sha256 都拒绝")
    void corruptManifestRejected() throws IOException {
        Path brokenJson = pack().add("apple", "{\"word\":\"apple\"}").write();
        Files.writeString(brokenJson.resolve(ContentPackManifest.FILE_NAME), "{not json");
        assertThatThrownBy(() -> requiredReader(brokenJson))
                .isInstanceOf(ContentPackException.class)
                .hasMessageContaining("解析失败");
        assertThat(new ContentPackReader(brokenJson.toString(), PROMPT_VERSION, false, true).available()).isFalse();

        Path wrongFormat = pack().add("apple", "{\"word\":\"apple\"}").write();
        Path wrongFormatManifest = wrongFormat.resolve(ContentPackManifest.FILE_NAME);
        Files.writeString(wrongFormatManifest, Files.readString(wrongFormatManifest)
                .replace(ContentPackManifest.SUPPORTED_FORMAT, "teachloop-content-pack-v0"));
        assertThatThrownBy(() -> requiredReader(wrongFormat))
                .isInstanceOf(ContentPackException.class)
                .hasMessageContaining("format");
        assertThat(new ContentPackReader(wrongFormat.toString(), PROMPT_VERSION, false, true).available()).isFalse();

        Path wrongCount = pack().add("apple", "{\"word\":\"apple\"}").count(2).write();
        assertThatThrownBy(() -> requiredReader(wrongCount))
                .isInstanceOf(ContentPackException.class)
                .hasMessageContaining("idx");

        Path wrongSha = pack().add("apple", "{\"word\":\"apple\"}").wrongPackSha().write();
        assertThatThrownBy(() -> requiredReader(wrongSha))
                .isInstanceOf(ContentPackException.class)
                .hasMessageContaining("sha256");
        // 关掉字节比对时仍能装载（结构校验照旧，损坏仍拒绝）
        ContentPackReader noVerify = new ContentPackReader(wrongSha.toString(), PROMPT_VERSION, true, false);
        assertThat(noVerify.available()).isTrue();
        assertThat(noVerify.get("en", "apple")).isPresent();
    }

    @Test
    @DisplayName("idx 损坏：字节数不符 / 未按 unsigned hash 升序 / offset 超出 pack 都拒绝")
    void corruptIndexRejected() throws IOException {
        Path truncated = pack().add("apple", "{\"word\":\"apple\"}").write();
        Path truncatedIdx = truncated.resolve(IDX_FILE);
        byte[] raw = Files.readAllBytes(truncatedIdx);
        Files.write(truncatedIdx, Arrays.copyOf(raw, raw.length - 1));
        assertThatThrownBy(() -> new ContentPackReader(truncated.toString(), PROMPT_VERSION, true, false))
                .isInstanceOf(ContentPackException.class)
                .hasMessageContaining("字节数");
        assertThat(new ContentPackReader(truncated.toString(), PROMPT_VERSION, false, false).available()).isFalse();

        Path unsorted = pack()
                .add("apple", "{\"word\":\"apple\"}")
                .add("banana", "{\"word\":\"banana\"}")
                .write();
        Path unsortedIdx = unsorted.resolve(IDX_FILE);
        byte[] blocks = Files.readAllBytes(unsortedIdx);
        byte[] first = Arrays.copyOfRange(blocks, 0, ContentPackIndex.RECORD_BYTES);
        byte[] second = Arrays.copyOfRange(blocks, ContentPackIndex.RECORD_BYTES, 2 * ContentPackIndex.RECORD_BYTES);
        System.arraycopy(second, 0, blocks, 0, ContentPackIndex.RECORD_BYTES);
        System.arraycopy(first, 0, blocks, ContentPackIndex.RECORD_BYTES, ContentPackIndex.RECORD_BYTES);
        Files.write(unsortedIdx, blocks);
        assertThatThrownBy(() -> new ContentPackReader(unsorted.toString(), PROMPT_VERSION, true, false))
                .isInstanceOf(ContentPackException.class)
                .hasMessageContaining("升序");

        Path outOfRange = pack().add("apple", "{\"word\":\"apple\"}").write();
        Path outOfRangeIdx = outOfRange.resolve(IDX_FILE);
        byte[] patched = Files.readAllBytes(outOfRangeIdx);
        ByteBuffer.wrap(patched).order(ByteOrder.BIG_ENDIAN)
                .putLong(8, Files.size(outOfRange.resolve(PACK_FILE)) + 100);
        Files.write(outOfRangeIdx, patched);
        assertThatThrownBy(() -> new ContentPackReader(outOfRange.toString(), PROMPT_VERSION, true, false))
                .isInstanceOf(ContentPackException.class)
                .hasMessageContaining("超出 pack");
    }

    @Test
    @DisplayName("manifest promptVersion 与配置不一致：不装载（换提示词必须出新包并升配置）")
    void promptVersionMismatchDegrades() throws IOException {
        Path dir = pack().add("apple", "{\"word\":\"apple\"}").write();
        ContentPackReader degraded = new ContentPackReader(dir.toString(), "vocab-content-v3", false, true);
        assertThat(degraded.available()).isFalse();
        assertThat(degraded.unavailableReason()).contains("promptVersion");
        assertThatThrownBy(() -> new ContentPackReader(dir.toString(), "vocab-content-v3", true, true))
                .isInstanceOf(ContentPackException.class)
                .hasMessageContaining("promptVersion");
    }

    @Test
    @DisplayName("10 万词合成包：装载 ≤1s、索引常驻 ≈4MB（≤8MB 目标）、按 id/按词形都能命中")
    void loadsHundredThousandWordsWithinBudget() throws IOException {
        int words = 100_000;
        PackFixture fixture = pack();
        for (int i = 0; i < words; i++) {
            String word = "word" + i;
            fixture.add(word, "{\"word\":\"" + word + "\",\"meanings\":{\"zh\":\"释义\"},\"examples\":[]}");
        }
        Path dir = fixture.write();

        long started = System.nanoTime();
        ContentPackReader reader = new ContentPackReader(dir.toString(), PROMPT_VERSION, true, true);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(reader.available()).isTrue();
        assertThat(reader.count()).isEqualTo(words);
        assertThat(reader.indexHeapBytes())
                .isEqualTo((long) words * ContentPackIndex.HEAP_BYTES_PER_ENTRY)
                .isLessThanOrEqualTo(8L * 1024 * 1024);
        assertThat(reader.get("en", "word99999")).isPresent();
        assertThat(reader.getById(1)).get()
                .extracting(StoredContent::normalizedForm).isEqualTo("word0");
        assertThat(elapsedMillis).as("10 万词装载耗时（毫秒）").isLessThan(1000L);
    }

    @Test
    @DisplayName("真实导入产物（fixtures-local/vocab-content，gitignored）存在则 round-trip，缺省跳过")
    void realPythonBuiltPackRoundTrips() {
        Path dir = realPackDir();
        Assumptions.assumeTrue(dir != null,
                "fixtures-local/vocab-content/manifest.json 不存在：跳过真实产物互操作校验");
        JsonNode manifest = new ObjectMapper().readTree(dir.resolve(ContentPackManifest.FILE_NAME));
        // 用包自己的 promptVersion 装载：本地包随生成脚本换版（v2.3 → v2.5），
        // 这条测的是「Python 构建器写出的产物 Java 端能读回来」，不是版本号本身。
        String promptVersion = manifest.get("promptVersion").asString();
        ContentPackReader reader = new ContentPackReader(dir.toString(), promptVersion, true, true);

        assertThat(reader.available()).isTrue();
        // 本地包大小由 fixtures 决定（v2.5 试点包只有 113 条），只校验与 manifest 一致。
        assertThat(reader.count()).isPositive()
                .isEqualTo(manifest.get("count").asInt());

        StoredContent first = reader.getById(1).orElseThrow();
        assertThat(first.source()).isEqualTo("pack");
        assertThat(first.normalizedForm()).isNotBlank();
        assertThat(first.json()).contains("\"word\"");
        assertThat(reader.get(first.language(), first.normalizedForm())).get()
                .extracting(StoredContent::id).isEqualTo(1L);
        StoredContent last = reader.getById(reader.count()).orElseThrow();
        assertThat(reader.get(last.language(), last.normalizedForm())).get()
                .extracting(StoredContent::id).isEqualTo((long) reader.count());
    }

    private ContentPackReader reader(Path dir) {
        return new ContentPackReader(dir.toString(), PROMPT_VERSION, true, true);
    }

    private ContentPackReader requiredReader(Path dir) {
        return new ContentPackReader(dir.toString(), PROMPT_VERSION, true, true);
    }

    private PackFixture pack() {
        return new PackFixture(tempDir.resolve("pack-" + (++fixtures)));
    }

    private static Path realPackDir() {
        List<Path> candidates = new ArrayList<>();
        String configured = System.getenv("VOCAB_CONTENT_PACK_DIR");
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        candidates.add(Path.of("..", "fixtures-local", "vocab-content"));
        candidates.add(Path.of("fixtures-local", "vocab-content"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve(ContentPackManifest.FILE_NAME))) {
                return candidate;
            }
        }
        return null;
    }

    /** 按契约手写 pack/idx/manifest 的小工具：add 顺序按 normalizedForm 顺序（与导入器一致）。 */
    private static final class PackFixture {

        private final Path dir;
        private final ByteArrayOutputStream pack = new ByteArrayOutputStream();
        private final List<Entry> entries = new ArrayList<>();
        private String language = "en";
        private String promptVersion = PROMPT_VERSION;
        private String version = "test-1";
        private String format = ContentPackManifest.SUPPORTED_FORMAT;
        private Integer count;
        private String packSha256Override;
        private boolean writeManifest = true;

        private record Entry(long hash, long offset, int length, long id) {
        }

        private PackFixture(Path dir) {
            this.dir = dir;
        }

        PackFixture add(String word, String json) {
            return addWithHash(hashOf(language, WordNormalizer.normalized(word)), json);
        }

        /** 模拟哈希碰撞：显式指定 hash，让多条记录落在同一个 hash 桶里。 */
        PackFixture addWithHash(long hash, String json) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            long id = entries.size() + 1L;
            long offset = pack.size();
            pack.writeBytes(bytes);
            entries.add(new Entry(hash, offset, bytes.length, id));
            return this;
        }

        PackFixture count(Integer value) {
            this.count = value;
            return this;
        }

        PackFixture wrongPackSha() {
            this.packSha256Override = "0".repeat(64);
            return this;
        }

        PackFixture withoutManifest() {
            this.writeManifest = false;
            return this;
        }

        Path write() throws IOException {
            Files.createDirectories(dir);
            List<Entry> ordered = new ArrayList<>(entries);
            ordered.sort((left, right) -> {
                int byHash = Long.compareUnsigned(left.hash(), right.hash());
                return byHash != 0 ? byHash : Long.compareUnsigned(left.id(), right.id());
            });
            byte[] packBytes = pack.toByteArray();
            byte[] idxBytes = idxBytes(ordered);
            String packSha = packSha256Override != null ? packSha256Override : sha256(packBytes);
            String idxSha = sha256(idxBytes);
            Files.write(dir.resolve(PACK_FILE), packBytes);
            Files.write(dir.resolve(IDX_FILE), idxBytes);
            if (writeManifest) {
                Files.writeString(dir.resolve(ContentPackManifest.FILE_NAME),
                        manifestJson(count != null ? count : ordered.size(), packSha, idxSha));
            }
            return dir;
        }

        private String manifestJson(int declaredCount, String packSha, String idxSha) {
            return "{\"format\":\"" + format + "\",\"language\":\"" + language + "\",\"version\":\"" + version
                    + "\",\"promptVersion\":\"" + promptVersion + "\",\"count\":" + declaredCount
                    + ",\"packFile\":\"" + PACK_FILE + "\",\"idxFile\":\"" + IDX_FILE + "\""
                    + ",\"packSha256\":\"" + packSha + "\",\"idxSha256\":\"" + idxSha + "\""
                    + ",\"createdAt\":\"2026-10-25T00:00:00Z\"}";
        }

        private static byte[] idxBytes(List<Entry> ordered) {
            ByteBuffer buffer = ByteBuffer.allocate(ordered.size() * ContentPackIndex.RECORD_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            for (Entry entry : ordered) {
                buffer.putLong(entry.hash());
                buffer.putLong(entry.offset());
                buffer.putInt(entry.length());
                buffer.putLong(entry.id());
            }
            return buffer.array();
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(ContentPackManifest.newDigest().digest(bytes));
    }

    /** 独立实现一遍 hash64 公式（不调用被测类的 hash64），避免读写两端同时错还互相印证。 */
    private static long hashOf(String language, String normalizedForm) {
        byte[] digest = ContentPackManifest.newDigest()
                .digest((language + "|" + normalizedForm).getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
    }
}
