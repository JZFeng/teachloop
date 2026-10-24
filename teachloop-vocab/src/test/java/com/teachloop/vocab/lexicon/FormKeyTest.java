package com.teachloop.vocab.lexicon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FormKeyTest {

    @Test
    @DisplayName("规范化：NFC + 小写 + 去首尾空格（spec §14.2）")
    void normalizedLowercasesAndTrims() {
        assertThat(FormKey.normalized("  RUN ")).isEqualTo("run");
        assertThat(FormKey.normalized("Éclat")).isEqualTo("éclat");
        assertThat(FormKey.normalized(null)).isEmpty();
    }

    @Test
    @DisplayName("去变音符：eclat 能命中 ÉCLAT，组合字符与预组合字符都处理")
    void foldedStripsDiacritics() {
        assertThat(FormKey.folded("éclat")).isEqualTo("eclat");
        assertThat(FormKey.folded("café")).isEqualTo("cafe");
        assertThat(FormKey.folded("plain")).isEqualTo("plain");
        // NFD 分解后的 e + U+0301 也要去掉组合符
        assertThat(FormKey.folded("cafe\u0301")).isEqualTo("cafe");
    }
}
