package com.teachloop.vocab.lexicon;

import java.util.List;

/**
 * 详情页词族区块要的数据（spec §14.4）：族首、频段、族频次，以及按频次降序、
 * 排除当前词、最多 N 个的同族词。
 *
 * <p>{@code matchedFolded=true} 表示用户输入没打变音符（{@code eclat} 命中 {@code éclat}）。
 */
public record WordFamily(
        String head,
        int band,
        long familyFreq,
        String matchedForm,
        long matchedFreq,
        boolean matchedFolded,
        List<Member> members) {

    public record Member(String form, long freq, boolean head) {
    }
}
