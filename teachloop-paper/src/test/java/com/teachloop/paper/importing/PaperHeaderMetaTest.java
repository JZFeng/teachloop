package com.teachloop.paper.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * T459 / AC-4.27④：「生成考题」时从原文头部读命题人 / 审题人 / 总分，只当提示。
 *
 * <p>断言用的是仓库里的真实卷 {@code src/test/resources/exam/english-grade10-2026-with-answers.md}：
 * 第二行就是 {@code **命题人：** 李璐  **审题人：** 刘嘉伦  **总分：** 150 分}。</p>
 */
class PaperHeaderMetaTest {

    private static byte[] fixture() throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/exam/english-grade10-2026-with-answers.md"));
    }

    @Test
    void readsAuthorReviewerAndTotalFromRealExamHeader() throws Exception {
        DocumentTextExtractor.ExtractedDocument document =
                DocumentTextExtractor.extract("english-grade10-2026-with-answers.md", fixture());

        PaperHeaderMeta.Hints hints = PaperHeaderMeta.parse(document.lines());

        assertThat(hints.authorName()).isEqualTo("李璐");
        assertThat(hints.reviewerName()).isEqualTo("刘嘉伦");
        assertThat(hints.targetTotalPoints()).isEqualByComparingTo("150");
        assertThat(hints.isEmpty()).isFalse();
    }

    @Test
    void readsHeaderFromRawMarkdownWithoutExtraction() throws Exception {
        String raw = new String(fixture(), StandardCharsets.UTF_8);

        PaperHeaderMeta.Hints hints = PaperHeaderMeta.parseText(raw);

        assertThat(hints.authorName()).isEqualTo("李璐");
        assertThat(hints.reviewerName()).isEqualTo("刘嘉伦");
        assertThat(hints.targetTotalPoints()).isEqualByComparingTo("150");
    }

    @Test
    void returnsNullsWhenHeaderHasNoLabels() {
        PaperHeaderMeta.Hints hints = PaperHeaderMeta.parseText("""
                1. 甲题
                A. 一
                B. 二
                答案
                1. A
                """);

        assertThat(hints.authorName()).isNull();
        assertThat(hints.reviewerName()).isNull();
        assertThat(hints.targetTotalPoints()).isNull();
        assertThat(hints.isEmpty()).isTrue();
    }

    @Test
    void onlyReadsTheFirstLinesAndIgnoresSectionFullMarks() {
        // 「满分 Y 分」出现在每个「第X部分」标题里；认了它就会把第一节的小节分当成全卷总分。
        PaperHeaderMeta.Hints hints = PaperHeaderMeta.parseText("""
                # 某校 2026 学年 10 月考试高一英语试卷

                ## 第一部分 听力（共两节，满分 30 分）

                1. What film will the speakers watch?
                A. A comedy film.
                答案
                1. A
                """);

        assertThat(hints.targetTotalPoints()).as("满分 ≠ 总分").isNull();
    }

    @Test
    void toleratesHalfWidthColonAndMissingLabels() {
        PaperHeaderMeta.Hints hints = PaperHeaderMeta.parseText("""
                命题人: 王老师
                总分: 120.5 分
                """);

        assertThat(hints.authorName()).isEqualTo("王老师");
        assertThat(hints.reviewerName()).isNull();
        assertThat(hints.targetTotalPoints()).isEqualByComparingTo("120.5");
    }
}
