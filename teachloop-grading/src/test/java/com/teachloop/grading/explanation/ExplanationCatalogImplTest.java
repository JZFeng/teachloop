package com.teachloop.grading.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.teachloop.grading.domain.ItemExplanation;
import com.teachloop.grading.domain.ItemExplanationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T115：学生反馈按 item + language + 当前 prompt 版本读缓存，没有就返回空。 */
class ExplanationCatalogImplTest {

    private static final UUID ITEM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ItemExplanationRepository repo;
    private ExplanationCatalogImpl catalog;

    @BeforeEach
    void setUp() {
        repo = mock(ItemExplanationRepository.class);
        catalog = new ExplanationCatalogImpl(repo);
    }

    @Test
    @DisplayName("命中：返回当前版本、done 状态的解析文本")
    void returnsStoredText() {
        when(repo.findByItemIdAndLanguageAndPromptVersion(ITEM_ID, "zh-CN", "explanation-v1"))
                .thenReturn(Optional.of(row("B 正确，因为只有 B 与题干条件一致。", "done")));

        assertThat(catalog.explanationOf(ITEM_ID, "zh-CN"))
                .contains("B 正确，因为只有 B 与题干条件一致。");
    }

    @Test
    @DisplayName("未命中：没有记录时返回空，由学生端显示「解析未生成」")
    void returnsEmptyWhenMissing() {
        when(repo.findByItemIdAndLanguageAndPromptVersion(ITEM_ID, "en", "explanation-v1"))
                .thenReturn(Optional.empty());

        assertThat(catalog.explanationOf(ITEM_ID, "en")).isEmpty();
    }

    @Test
    @DisplayName("空白文本不算解析：不返回编造/空内容")
    void ignoresBlankText() {
        when(repo.findByItemIdAndLanguageAndPromptVersion(ITEM_ID, "zh-CN", "explanation-v1"))
                .thenReturn(Optional.of(row("   ", "done")));

        assertThat(catalog.explanationOf(ITEM_ID, "zh-CN")).isEmpty();
    }

    @Test
    @DisplayName("只有 done 状态可读：待重新生成的行不返回")
    void ignoresNotDoneState() {
        when(repo.findByItemIdAndLanguageAndPromptVersion(ITEM_ID, "zh-CN", "explanation-v1"))
                .thenReturn(Optional.of(row("旧解析", "stale")));

        assertThat(catalog.explanationOf(ITEM_ID, "zh-CN")).isEmpty();
    }

    @Test
    @DisplayName("空语言按 zh-CN 归一，与写入端同一口径")
    void normalizesBlankLanguage() {
        when(repo.findByItemIdAndLanguageAndPromptVersion(ITEM_ID, "zh-CN", "explanation-v1"))
                .thenReturn(Optional.of(row("解析", "done")));

        assertThat(catalog.explanationOf(ITEM_ID, null)).contains("解析");
    }

    private static ItemExplanation row(String text, String state) {
        return new ItemExplanation(UUID.randomUUID(), ITEM_ID, "zh-CN", "explanation-v1", text, state,
                Instant.parse("2026-10-20T00:00:00Z"));
    }
}
