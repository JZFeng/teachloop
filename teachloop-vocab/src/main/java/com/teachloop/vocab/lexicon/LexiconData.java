package com.teachloop.vocab.lexicon;

import java.util.List;

/** 一次成功读入的词表索引：manifest + 两张表的内容。 */
public record LexiconData(LexiconManifest manifest, List<FamilyRow> families, List<FormRow> forms) {

    public LexiconData {
        families = List.copyOf(families);
        forms = List.copyOf(forms);
    }

    public int familyCount() {
        return families.size();
    }

    public int formCount() {
        return forms.size();
    }
}
