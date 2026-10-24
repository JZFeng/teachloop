package com.teachloop.vocab.lexicon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WordFamilyIndexTest {

    private static LexiconData sampleData() {
        LexiconManifest manifest = new LexiconManifest(
                LexiconReader.SUPPORTED_FORMAT,
                "a".repeat(64),
                new LexiconManifest.Counts(2, 5),
                new LexiconManifest.Artifact("families.tsv", "b".repeat(64), 1, 2),
                new LexiconManifest.Artifact("forms.tsv", "c".repeat(64), 1, 5),
                List.of());
        List<FamilyRow> families = List.of(
                new FamilyRow(1L, "run", 1, 100),
                new FamilyRow(2L, "éclat", 3, 42));
        List<FormRow> forms = List.of(
                new FormRow("run", "run", 1L, 60, true),
                new FormRow("running", "running", 1L, 30, false),
                new FormRow("runs", "runs", 1L, 8, false),
                new FormRow("ran", "ran", 1L, 2, false),
                new FormRow("éclat", "eclat", 2L, 42, true));
        return new LexiconData(manifest, families, forms);
    }

    private static WordFamilyIndex index(int maxMembers) {
        WordFamilyIndex index = new WordFamilyIndex(maxMembers, 0);
        index.beginLoading();
        index.install(sampleData());
        return index;
    }

    @Test
    @DisplayName("精确命中：run 属于第 1 千词族，同族词按频次降序且排除当前词")
    void exactLookup() {
        WordFamily family = index(8).lookup(" RUN ").orElseThrow();
        assertThat(family.head()).isEqualTo("run");
        assertThat(family.band()).isEqualTo(1);
        assertThat(family.familyFreq()).isEqualTo(100);
        assertThat(family.matchedForm()).isEqualTo("run");
        assertThat(family.matchedFolded()).isFalse();
        assertThat(family.members()).extracting(WordFamily.Member::form)
                .containsExactly("running", "runs", "ran");
    }

    @Test
    @DisplayName("去变音符兜底：eclat 命中 éclat，并标记 matchedFolded")
    void foldedLookup() {
        WordFamily family = index(8).lookup("eclat").orElseThrow();
        assertThat(family.head()).isEqualTo("éclat");
        assertThat(family.matchedForm()).isEqualTo("éclat");
        assertThat(family.matchedFolded()).isTrue();
        assertThat(family.band()).isEqualTo(3);
        // 族里只有它自己，排除后为空
        assertThat(family.members()).isEmpty();
    }

    @Test
    @DisplayName("同族词最多 N 个（teachloop.vocab.word-family.max-members）")
    void membersAreCapped() {
        assertThat(index(2).lookup("run").orElseThrow().members()).hasSize(2);
        assertThat(index(0).lookup("run").orElseThrow().members()).isEmpty();
    }

    @Test
    @DisplayName("未命中返回空（由异步内容生成兜底），空输入不爆")
    void missReturnsEmpty() {
        WordFamilyIndex index = index(8);
        assertThat(index.lookup("zyzzyva")).isEmpty();
        assertThat(index.lookup("   ")).isEmpty();
        assertThat(index.lookup(null)).isEmpty();
    }

    @Test
    @DisplayName("装载前的查询按不可用处理；重载失败保留旧快照")
    void availabilityLifecycle() {
        WordFamilyIndex index = new WordFamilyIndex(8, 0);
        index.beginLoading();
        assertThat(index.available()).isFalse();
        assertThat(index.lookup("run")).isEmpty();

        index.install(sampleData());
        assertThat(index.available()).isTrue();
        assertThat(index.familyCount()).isEqualTo(2);
        assertThat(index.formCount()).isEqualTo(5);

        index.markUnavailable(); // 模拟重载失败：旧数据继续可用，不清空线上词族
        assertThat(index.available()).isTrue();
        assertThat(index.lookup("run")).isPresent();
    }
}
