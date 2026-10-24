package com.teachloop.vocab.lexicon;

import java.util.List;

/** T392 测试用词表：覆盖三态所需的高频词与易混词，不依赖真实词表。 */
public final class LexiconTestData {

    private LexiconTestData() {
    }

    public static LexiconData data() {
        LexiconManifest manifest = new LexiconManifest(
                LexiconReader.SUPPORTED_FORMAT,
                "a".repeat(64),
                new LexiconManifest.Counts(5, 10),
                new LexiconManifest.Artifact("families.tsv", "b".repeat(64), 1, 5),
                new LexiconManifest.Artifact("forms.tsv", "c".repeat(64), 1, 10),
                List.of());
        List<FamilyRow> families = List.of(
                new FamilyRow(1L, "receive", 1, 100),
                new FamilyRow(2L, "relieve", 2, 80),
                new FamilyRow(3L, "clever", 2, 60),
                new FamilyRow(4L, "accommodate", 5, 40),
                new FamilyRow(5L, "apple", 1, 200));
        List<FormRow> forms = List.of(
                new FormRow("receive", "receive", 1L, 90, true),
                new FormRow("receives", "receives", 1L, 6, false),
                new FormRow("received", "received", 1L, 4, false),
                new FormRow("relieve", "relieve", 2L, 70, true),
                new FormRow("relieved", "relieved", 2L, 10, false),
                new FormRow("clever", "clever", 3L, 60, true),
                new FormRow("cleverer", "cleverer", 3L, 3, false),
                new FormRow("accommodate", "accommodate", 4L, 30, true),
                new FormRow("accommodates", "accommodates", 4L, 10, false),
                new FormRow("apple", "apple", 5L, 200, true));
        return new LexiconData(manifest, families, forms);
    }

    public static WordFamilyIndex installedIndex() {
        return installedIndex(data());
    }

    public static WordFamilyIndex installedIndex(LexiconData data) {
        WordFamilyIndex index = new WordFamilyIndex(8, 0);
        index.beginLoading();
        index.install(data);
        return index;
    }

    public static WordFamilyIndex unavailableIndex() {
        return new WordFamilyIndex(8, 0);
    }
}
