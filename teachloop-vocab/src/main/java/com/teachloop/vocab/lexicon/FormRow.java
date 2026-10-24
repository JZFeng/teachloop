package com.teachloop.vocab.lexicon;

/**
 * 一个词形。
 *
 * <p>{@code formFreq} 是该词形自身的频次；族首词的自身频次由「族频次 − 成员合计」推导，
 * 源数据里 4 个噪声族会得到负数（spec §14.2），展示时只给族频次。
 */
public record FormRow(String normalizedForm, String foldedForm, long familyId, long formFreq, boolean head) {
}
