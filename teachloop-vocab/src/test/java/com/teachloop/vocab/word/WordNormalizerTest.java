package com.teachloop.vocab.word;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T393：规范化口径（FR-093 + 任务验收的大小写/全角/变音符去重）。 */
class WordNormalizerTest {

    @Test
    @DisplayName("展示形态：NFC + 全角转半角 + 去首尾空格")
    void display() {
        assertThat(WordNormalizer.display("  Ａpple ")).isEqualTo("Apple");
        assertThat(WordNormalizer.display("ＣＡＦＥ")).isEqualTo("CAFE");
        assertThat(WordNormalizer.display("cafe\u0301")).isEqualTo("café");
    }

    @Test
    @DisplayName("去重键：大小写 / 全角 / 变音符归一")
    void normalized() {
        assertThat(WordNormalizer.normalized("APPLE")).isEqualTo("apple");
        assertThat(WordNormalizer.normalized("ＣＡＦＥ")).isEqualTo("cafe");
        assertThat(WordNormalizer.normalized("Café")).isEqualTo("cafe");
        assertThat(WordNormalizer.normalized("Résumé")).isEqualTo("resume");
        // 不做词形还原：run 与 running 仍是两个词（spec A-6）
        assertThat(WordNormalizer.normalized("running")).isEqualTo("running");
    }

    @Test
    @DisplayName("正面校验：非空且 ≤64 字符（按 code point）")
    void valid() {
        assertThat(WordNormalizer.valid("apple")).isTrue();
        assertThat(WordNormalizer.valid("  ")).isFalse();
        assertThat(WordNormalizer.valid("a".repeat(64))).isTrue();
        assertThat(WordNormalizer.valid("a".repeat(65))).isFalse();
    }
}
