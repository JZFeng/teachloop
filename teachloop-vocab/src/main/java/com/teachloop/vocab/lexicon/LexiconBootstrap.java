package com.teachloop.vocab.lexicon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时装载词表索引（T388）：读产物 → 与 {@code vocab_lexicon_meta} 的指纹比对 →
 * 变了才 upsert 两张表 → 建内存索引。
 *
 * <p>失败路径（spec §8）：产物缺失、manifest 损坏、sha256 对不上、写库失败，
 * 一律记 warn 并把词族区块降级为「不可用」，**不阻塞应用启动**，也不影响详情页其余内容。
 */
@Component
public class LexiconBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LexiconBootstrap.class);

    public enum LoadResult {
        /** teachloop.vocab.lexicon.enabled=false。 */
        DISABLED,
        /** 产物目录或 manifest 不存在（没跑 make vocab-lexicon）。 */
        MISSING,
        /** 指纹与计数都没变，跳过写库。 */
        UNCHANGED,
        /** 已写库并替换内存索引。 */
        APPLIED,
        /** 读取/校验/写库失败，词族区块降级。 */
        FAILED
    }

    private final boolean enabled;
    private final Path dir;
    private final LexiconReader reader;
    private final LexiconStore store;
    private final WordFamilyIndex index;
    private final Clock clock;

    public LexiconBootstrap(
            @Value("${teachloop.vocab.lexicon.enabled:true}") boolean enabled,
            @Value("${teachloop.vocab.lexicon.dir:./fixtures-local/vocab-lexicon}") String dir,
            LexiconReader reader,
            LexiconStore store,
            WordFamilyIndex index,
            Clock clock) {
        this.enabled = enabled;
        this.dir = Path.of(dir);
        this.reader = reader;
        this.store = store;
        this.index = index;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        load(dir);
    }

    public LoadResult load(Path directory) {
        if (!enabled) {
            index.markUnavailable();
            log.info("词表索引已关闭（teachloop.vocab.lexicon.enabled=false），词族区块不展示");
            return LoadResult.DISABLED;
        }
        index.beginLoading();
        try {
            if (!Files.isRegularFile(directory.resolve("manifest.json"))) {
                index.markUnavailable();
                log.warn("词表索引不存在 dir={}，词族区块降级隐藏（先生成：make vocab-lexicon）", directory);
                return LoadResult.MISSING;
            }
            LexiconData data = reader.read(directory);
            Optional<AppliedManifest> applied = store.applied();
            boolean unchanged = applied
                    .filter(meta -> meta.contentSha256().equals(data.manifest().contentSha256())
                            && meta.familyCount() == data.familyCount()
                            && meta.formCount() == data.formCount())
                    .isPresent();
            if (unchanged) {
                index.install(data);
                log.info("词表索引未变化（{} 族 / {} 形，sha256={}），跳过写库",
                        data.familyCount(), data.formCount(), shortHash(data.manifest().contentSha256()));
                return LoadResult.UNCHANGED;
            }
            store.apply(data, clock.instant());
            index.install(data);
            log.info("词表索引已装载：{} 族 / {} 形（sha256={}）",
                    data.familyCount(), data.formCount(), shortHash(data.manifest().contentSha256()));
            return LoadResult.APPLIED;
        } catch (IOException | RuntimeException ex) {
            index.markUnavailable();
            log.warn("词表索引装载失败 dir={}，词族区块降级隐藏：{}", directory, ex.toString());
            return LoadResult.FAILED;
        }
    }

    private static String shortHash(String sha256) {
        return sha256 == null || sha256.length() < 12 ? String.valueOf(sha256) : sha256.substring(0, 12) + "…";
    }
}
