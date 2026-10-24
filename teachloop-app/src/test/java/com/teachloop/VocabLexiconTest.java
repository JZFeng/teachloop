package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.lexicon.AppliedManifest;
import com.teachloop.vocab.lexicon.LexiconBootstrap;
import com.teachloop.vocab.lexicon.LexiconStore;
import com.teachloop.vocab.lexicon.WordFamily;
import com.teachloop.vocab.lexicon.WordFamilyIndex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * T388 集成验收：真实 Postgres + 提交在测试资源里的合成词表（5 族 / 10 形）。
 *
 * <p>验收对应 tasks.md T388：
 * <ul>
 *   <li>28,276 族 / 80,579 形 的规模由真实词表在本机跑脚本证明（见执行记录）；这里用
 *       小数据验证同一条管线：读取 → sha256 校验 → 幂等 upsert → 内存查询；</li>
 *   <li>run / running 命中同一族；</li>
 *   <li>éclat 命中 ÉCLAT（去变音符；真实词表里该词在未收的补充表，见执行记录的决定）；</li>
 *   <li>manifest 变化才重建，不变则跳过写库。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class VocabLexiconTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("teachloop.vocab.lexicon.dir", () -> fixture().toString());
        registry.add("teachloop.grading.poll-ms", () -> "3600000");
        registry.add("teachloop.grading.dispatch-ms", () -> "3600000");
        registry.add("teachloop.grading.sweep-ms", () -> "3600000");
    }

    @Autowired
    LexiconStore store;

    @Autowired
    WordFamilyIndex index;

    @Autowired
    LexiconBootstrap bootstrap;

    @Test
    @DisplayName("启动装载：DB 计数、meta 指纹、精确查询与去变音符查询")
    void loadsIndexAndAnswersLookups() throws Exception {
        assertThat(index.available()).isTrue();
        assertThat(store.familyCount()).isEqualTo(5);
        assertThat(store.formCount()).isEqualTo(10);

        AppliedManifest applied = store.applied().orElseThrow();
        assertThat(applied.contentSha256()).isEqualTo(manifestSha(fixture()));
        assertThat(applied.familyCount()).isEqualTo(5);
        assertThat(applied.formCount()).isEqualTo(10);

        WordFamily run = index.lookup("RUN").orElseThrow();
        assertThat(run.head()).isEqualTo("run");
        assertThat(run.band()).isEqualTo(1);
        assertThat(run.members()).extracting(WordFamily.Member::form).contains("running");
        assertThat(index.lookup("running").orElseThrow().head()).isEqualTo("run");

        WordFamily folded = index.lookup("eclat").orElseThrow();
        assertThat(folded.head()).isEqualTo("éclat");
        assertThat(folded.matchedFolded()).isTrue();
        assertThat(index.lookup("ÉCLAT").orElseThrow().head()).isEqualTo("éclat");

        assertThat(index.lookup("zyzzyva")).isEmpty();
    }

    @Test
    @DisplayName("manifest 没变：跳过写库，applied_at 不变")
    void unchangedManifestSkipsWrites() throws Exception {
        AppliedManifest before = store.applied().orElseThrow();
        assertThat(bootstrap.load(fixture())).isEqualTo(LexiconBootstrap.LoadResult.UNCHANGED);
        AppliedManifest after = store.applied().orElseThrow();
        assertThat(after.appliedAt()).isEqualTo(before.appliedAt());
        assertThat(after.contentSha256()).isEqualTo(before.contentSha256());
    }

    @Test
    @DisplayName("manifest 变了：重写并删掉消失的族，恢复后原词回来")
    void changedManifestRebuildsAndDropsStaleRows() throws Exception {
        Path changed = copyFixtureRemovingFamily("heft");
        try {
            assertThat(bootstrap.load(changed)).isEqualTo(LexiconBootstrap.LoadResult.APPLIED);
            assertThat(store.familyCount()).isEqualTo(4);
            assertThat(store.formCount()).isEqualTo(7);
            assertThat(store.applied().orElseThrow().contentSha256()).isEqualTo("d".repeat(64));
            assertThat(index.lookup("heft")).isEmpty();
            assertThat(index.lookup("run")).isPresent();

            assertThat(bootstrap.load(fixture())).isEqualTo(LexiconBootstrap.LoadResult.APPLIED);
            assertThat(store.familyCount()).isEqualTo(5);
            assertThat(store.formCount()).isEqualTo(10);
            assertThat(index.lookup("heft")).isPresent();
        } finally {
            deleteRecursively(changed);
        }
    }

    // ---------- 测试夹具 ----------

    private static Path fixture() {
        try {
            return Path.of(VocabLexiconTest.class.getResource("/vocab-lexicon").toURI());
        } catch (Exception ex) {
            throw new IllegalStateException("测试资源 vocab-lexicon 缺失", ex);
        }
    }

    private static String manifestSha(Path dir) throws IOException {
        return MAPPER.readTree(dir.resolve("manifest.json")).get("content_sha256").asString();
    }

    /** 复制夹具，删掉 head=heft 的族及其词形，重算产物 sha256/bytes/rows 与 counts。 */
    private static Path copyFixtureRemovingFamily(String head) throws IOException {
        Path source = fixture();
        Path target = Files.createTempDirectory("vocab-lexicon-");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(source)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path file : files) {
            Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }

        Path familiesPath = target.resolve("lexicon-families.tsv");
        Path formsPath = target.resolve("lexicon-forms.tsv");
        String[] familyRows = Files.readString(familiesPath, StandardCharsets.UTF_8).split("\n");
        String removedId = null;
        List<String> keptFamilies = new ArrayList<>();
        for (String row : familyRows) {
            if (row.isEmpty()) {
                continue;
            }
            String[] cells = row.split("\t", -1);
            if (cells[1].equals(head)) {
                removedId = cells[0];
            } else {
                keptFamilies.add(row);
            }
        }
        assertThat(removedId).as("夹具里应有 head=%s 的族", head).isNotNull();
        Files.write(familiesPath, (String.join("\n", keptFamilies) + "\n").getBytes(StandardCharsets.UTF_8));

        List<String> keptForms = new ArrayList<>();
        for (String row : Files.readString(formsPath, StandardCharsets.UTF_8).split("\n")) {
            if (row.isEmpty()) {
                continue;
            }
            String[] cells = row.split("\t", -1);
            if (!cells[2].equals(removedId)) {
                keptForms.add(row);
            }
        }
        Files.write(formsPath, (String.join("\n", keptForms) + "\n").getBytes(StandardCharsets.UTF_8));

        Path manifestPath = target.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) MAPPER.readTree(manifestPath);
        ObjectNode artifacts = (ObjectNode) manifest.get("artifacts");
        ObjectNode families = (ObjectNode) artifacts.get("families");
        ObjectNode forms = (ObjectNode) artifacts.get("forms");
        families.put("bytes", Files.size(familiesPath));
        families.put("rows", keptFamilies.size());
        families.put("sha256", sha256(familiesPath));
        forms.put("bytes", Files.size(formsPath));
        forms.put("rows", keptForms.size());
        forms.put("sha256", sha256(formsPath));
        ObjectNode counts = (ObjectNode) manifest.get("counts");
        counts.put("families", keptFamilies.size());
        counts.put("forms", keptForms.size());
        manifest.put("content_sha256", "d".repeat(64));
        MAPPER.writeValue(manifestPath, manifest);
        return target;
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

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // 测试临时目录清理失败不影响断言
                        }
                    });
        }
    }
}
