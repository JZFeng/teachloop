package com.teachloop.vocab.lexicon;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 启动装载的决策逻辑（不连数据库）：缺失/未变化/已应用/失败/重载失败保留旧索引。 */
class LexiconBootstrapTest {

    private static final String SHA = "a".repeat(64);

    @TempDir
    java.nio.file.Path dir;

    private final LexiconReader reader = mock(LexiconReader.class);
    private final LexiconStore store = mock(LexiconStore.class);
    private final WordFamilyIndex index = new WordFamilyIndex(8, 0);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-23T01:00:00Z"), ZoneOffset.UTC);

    private LexiconBootstrap bootstrap(boolean enabled) {
        return new LexiconBootstrap(enabled, dir.toString(), reader, store, index, clock);
    }

    private static LexiconData data() {
        LexiconManifest manifest = new LexiconManifest(
                LexiconReader.SUPPORTED_FORMAT, SHA,
                new LexiconManifest.Counts(2, 4),
                new LexiconManifest.Artifact("families.tsv", "b".repeat(64), 10, 2),
                new LexiconManifest.Artifact("forms.tsv", "c".repeat(64), 20, 4),
                List.of());
        return new LexiconData(manifest,
                List.of(new FamilyRow(1L, "run", 1, 100),
                        new FamilyRow(2L, "eclat", 2, 10)),
                List.of(new FormRow("run", "run", 1L, 90, true),
                        new FormRow("running", "running", 1L, 8, false),
                        new FormRow("runs", "runs", 1L, 2, false),
                        new FormRow("eclat", "eclat", 2L, 10, true)));
    }

    private void manifestPresent() throws java.io.IOException {
        java.nio.file.Files.writeString(dir.resolve("manifest.json"), "{}");
    }

    @Test
    @DisplayName("开关关闭：不读文件、不碰数据库，词族区块不展示")
    void disabled() {
        assertThat(bootstrap(false).load(dir)).isEqualTo(LexiconBootstrap.LoadResult.DISABLED);
        verifyNoInteractions(reader, store);
        assertThat(index.available()).isFalse();
    }

    @Test
    @DisplayName("产物缺失：记 MISSING 并降级，不抛异常")
    void missing() {
        assertThat(bootstrap(true).load(dir)).isEqualTo(LexiconBootstrap.LoadResult.MISSING);
        verifyNoInteractions(reader, store);
        assertThat(index.available()).isFalse();
    }

    @Test
    @DisplayName("指纹与计数一致：跳过写库，只装内存")
    void unchanged() throws Exception {
        manifestPresent();
        when(reader.read(any())).thenReturn(data());
        when(store.applied()).thenReturn(java.util.Optional.of(
                new AppliedManifest(SHA, 2, 4, Instant.EPOCH)));

        assertThat(bootstrap(true).load(dir)).isEqualTo(LexiconBootstrap.LoadResult.UNCHANGED);
        verify(store, never()).apply(any(), any());
        assertThat(index.lookup("run")).isPresent();
    }

    @Test
    @DisplayName("指纹缺失或不同：写库并替换内存索引")
    void applied() throws Exception {
        manifestPresent();
        when(reader.read(any())).thenReturn(data());
        when(store.applied()).thenReturn(java.util.Optional.empty());

        assertThat(bootstrap(true).load(dir)).isEqualTo(LexiconBootstrap.LoadResult.APPLIED);
        verify(store).apply(eq(data()), eq(Instant.parse("2026-10-23T01:00:00Z")));
        assertThat(index.lookup("running")).isPresent();
    }

    @Test
    @DisplayName("读取失败：FAILED + 降级，不阻塞应用")
    void failed() throws Exception {
        manifestPresent();
        when(reader.read(any())).thenThrow(new LexiconException("产物被改动"));

        assertThat(bootstrap(true).load(dir)).isEqualTo(LexiconBootstrap.LoadResult.FAILED);
        assertThat(index.available()).isFalse();
    }

    @Test
    @DisplayName("重载失败不清空已可用的旧索引")
    void failedReloadKeepsPreviousSnapshot() throws Exception {
        manifestPresent();
        when(reader.read(any())).thenReturn(data());
        when(store.applied()).thenReturn(java.util.Optional.empty());
        LexiconBootstrap bootstrap = bootstrap(true);
        assertThat(bootstrap.load(dir)).isEqualTo(LexiconBootstrap.LoadResult.APPLIED);

        when(reader.read(any())).thenThrow(new LexiconException("文件坏了"));
        assertThat(bootstrap.load(dir)).isEqualTo(LexiconBootstrap.LoadResult.FAILED);
        assertThat(index.lookup("run")).isPresent();
    }
}
