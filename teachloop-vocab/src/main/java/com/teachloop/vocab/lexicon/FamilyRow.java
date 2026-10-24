package com.teachloop.vocab.lexicon;

/** 一个词族：{@code head} 已规范化（小写），{@code band} 是 BNC 频段（1 = 第 1 千词族）。 */
public record FamilyRow(long id, String head, int band, long familyFreq) {
}
