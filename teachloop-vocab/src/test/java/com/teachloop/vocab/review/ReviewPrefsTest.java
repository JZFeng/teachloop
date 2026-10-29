package com.teachloop.vocab.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** T552：偏好的归一化与容错（AC-88.2 / 88.6 / 88.8）。 */
class ReviewPrefsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ReviewPrefs parse(String json) {
        return ReviewPrefs.parse(MAPPER.readTree(json), 20, 200);
    }

    @Test
    @DisplayName("缺省值 = 改造前的行为（20 / 混合 / 8 块默认序 / 全显示）")
    void defaultsMatchLegacyBehaviour() {
        ReviewPrefs prefs = ReviewPrefs.defaults(20);
        assertThat(prefs.dailyNewCards()).isEqualTo(20);
        assertThat(prefs.strategy()).isEqualTo(ReviewPrefs.MIXED);
        assertThat(prefs.blocks()).containsExactlyElementsOf(ReviewPrefs.ALL_BLOCKS);
        assertThat(prefs.hidden()).isEmpty();
    }

    @Test
    @DisplayName("任务量 clamp 到 3…硬上限")
    void clampsCards() {
        assertThat(parse("{\"dailyNewCards\": -5}").dailyNewCards()).isEqualTo(3);
        assertThat(parse("{\"dailyNewCards\": 9999}").dailyNewCards()).isEqualTo(200);
        assertThat(parse("{\"dailyNewCards\": 50}").dailyNewCards()).isEqualTo(50);
    }

    @Test
    @DisplayName("未知策略回落 MIXED，大小写不敏感")
    void normalizesStrategy() {
        assertThat(parse("{\"strategy\":\"review_first\"}").strategy()).isEqualTo(ReviewPrefs.REVIEW_FIRST);
        assertThat(parse("{\"strategy\":\"whatever\"}").strategy()).isEqualTo(ReviewPrefs.MIXED);
        assertThat(parse("{}").strategy()).isEqualTo(ReviewPrefs.MIXED);
    }

    @Test
    @DisplayName("顺序：未知 key 丢弃、缺失 key 追加到末尾、重复去重")
    void normalizesBlocks() {
        ReviewPrefs prefs = parse("{\"blocks\":[\"examples\",\"nope\",\"examples\",\"core\"]}");
        assertThat(prefs.blocks()).startsWith("examples", "core");
        assertThat(prefs.blocks()).hasSize(ReviewPrefs.ALL_BLOCKS.size());
        assertThat(prefs.blocks()).doesNotContain("nope");
        assertThat(prefs.blocks()).contains("synonyms", "derivation");
    }

    @Test
    @DisplayName("关掉的块放在 hidden，不靠「缺席」表达；非法值被忽略")
    void normalizesHidden() {
        ReviewPrefs prefs = parse("{\"hidden\":[\"antonyms\",\"bogus\"]}");
        assertThat(prefs.hidden()).containsExactly("antonyms");
        assertThat(prefs.blocks()).hasSize(ReviewPrefs.ALL_BLOCKS.size());
    }

    @Test
    @DisplayName("写坏的对象不抛异常，回落默认")
    void tolerantToGarbage() {
        assertThat(ReviewPrefs.parse(MAPPER.readTree("\"not an object\""), 20, 200).dailyNewCards())
                .isEqualTo(20);
        assertThat(ReviewPrefs.parse(null, 20, 200).strategy()).isEqualTo(ReviewPrefs.MIXED);
    }
}
