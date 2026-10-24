package com.teachloop.vocab.lexicon;

import java.time.Instant;

/** 数据库里记录的「上次写进 vocab_lexicon_* 的是哪份产物」。 */
public record AppliedManifest(String contentSha256, int familyCount, int formCount, Instant appliedAt) {
}
