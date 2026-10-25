package com.teachloop.grading.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 评语字段名前缀归一：界面自己渲染「优点」「建议」，正文里重复的字段名要剥掉。 */
class ReviewTextTest {

    @Test
    @DisplayName("中文前缀：剥掉一次，重复出现也剥干净")
    void stripsChineseLabels() {
        assertThat(ReviewText.stripLabel("优点：活动分层清楚，引用原文体现 extensive reading。"))
                .isEqualTo("活动分层清楚，引用原文体现 extensive reading。");
        assertThat(ReviewText.stripLabel("优点：优点：活动分层清楚。")).isEqualTo("活动分层清楚。");
        assertThat(ReviewText.stripLabel("建议: 补充一个具体的课堂活动例子。"))
                .isEqualTo("补充一个具体的课堂活动例子。");
        assertThat(ReviewText.stripLabel("建议：\n补充一个具体的课堂活动例子。"))
                .isEqualTo("补充一个具体的课堂活动例子。");
    }

    @Test
    @DisplayName("英文前缀：Strength / Suggestion 大小写与空格都容忍")
    void stripsEnglishLabels() {
        assertThat(ReviewText.stripLabel("Strength: uses a concrete example from the essay."))
                .isEqualTo("uses a concrete example from the essay.");
        assertThat(ReviewText.stripLabel("suggestion :  add one more example."))
                .isEqualTo("add one more example.");
        assertThat(ReviewText.stripLabel("Strengths: clear structure.")).isEqualTo("clear structure.");
    }

    @Test
    @DisplayName("没有冒号的普通句子不当作前缀；null 仍是 null")
    void keepsPlainText() {
        assertThat(ReviewText.stripLabel("整体完整，英文表达清晰。")).isEqualTo("整体完整，英文表达清晰。");
        assertThat(ReviewText.stripLabel("优点很重要，但还要更具体。")).isEqualTo("优点很重要，但还要更具体。");
        assertThat(ReviewText.stripLabel("  ")).isEmpty();
        assertThat(ReviewText.stripLabel(null)).isNull();
    }
}
