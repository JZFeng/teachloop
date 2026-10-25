package com.teachloop.vocab.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.lexicon.LexiconTestData;
import com.teachloop.vocab.lists.VocabularyListRepository;
import com.teachloop.vocab.spelling.SpellingChecker;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T391/T393：预览把 cleanups、拼写三态与「新建/重复/失败」计数一起透出，不静默。 */
class ImportPreviewEndpointTest {

    private final WordImportService importService = mock(WordImportService.class);
    private final VocabularyListRepository lists = mock(VocabularyListRepository.class);
    private final ImportPreviewEndpoint endpoint = new ImportPreviewEndpoint(
            new DictionaryTextParser(),
            new SpellingChecker(LexiconTestData.installedIndex()),
            importService, lists, Clock.systemUTC(), 100);
    private final CurrentUser me = new CurrentUser(UUID.randomUUID(), "STUDENT", "示例学生");

    @Test
    @DisplayName("粘贴文本预览：token、去重、清洗记录、计数都在响应里")
    void previewCarriesCleanupsAndCounts() {
        when(importService.preview(any(), any(), any())).thenReturn(
                new WordImportService.PreviewResult(
                        new WordImportService.Counts(3, 2, 1, 0),
                        List.of(new WordImportService.LineError(1, "waytoolong",
                                "正面为空或超过 64 字符"))));
        var response = endpoint.preview(new ImportTextRequest(
                "1. \"take off\", apple n.，brave", "en", null, null), me);
        assertThat(response.distinctWords()).containsExactly("take off", "apple", "brave");
        assertThat(response.cleanups()).extracting(ImportPreviewEndpoint.CleanupView::rule)
                .contains("去行首序号/项目符号", "去引号", "去词性标注", "全角标点转半角");
        assertThat(response.counts().total()).isEqualTo(3);
        assertThat(response.counts().created()).isEqualTo(2);
        assertThat(response.lineErrors()).hasSize(1);
        assertThat(response.skipped()).isEmpty();
    }

    @Test
    @DisplayName("T392：预览同时给出每个词的拼写三态与候选项")
    void previewCarriesSpelling() {
        when(importService.preview(any(), any(), any())).thenReturn(
                new WordImportService.PreviewResult(new WordImportService.Counts(3, 2, 1, 0), List.of()));
        var response = endpoint.preview(new ImportTextRequest(
                "apple, recieve, zyzzyva", "en", null, null), me);
        assertThat(response.spellingChecked()).isTrue();
        var byWord = response.spelling().stream().collect(java.util.stream.Collectors.toMap(
                ImportPreviewEndpoint.SpellingView::word, view -> view));
        assertThat(byWord.get("apple").state()).isEqualTo(SpellingChecker.State.OK);
        assertThat(byWord.get("recieve").state()).isEqualTo(SpellingChecker.State.SUSPECT);
        assertThat(byWord.get("recieve").candidates())
                .extracting(ImportPreviewEndpoint.CandidateView::form).contains("receive");
        assertThat(byWord.get("zyzzyva").state()).isEqualTo(SpellingChecker.State.UNKNOWN);
    }

    @Test
    @DisplayName("空请求返回空结果，不抛异常")
    void emptyRequest() {
        when(importService.preview(any(), any(), any())).thenReturn(
                new WordImportService.PreviewResult(new WordImportService.Counts(0, 0, 0, 0), List.of()));
        var response = endpoint.preview(null, me);
        assertThat(response.lineCount()).isZero();
        assertThat(response.tokens()).isEmpty();
        assertThat(response.spelling()).isEmpty();
    }

    @Test
    @DisplayName("提交沿用同一条管线，返回批次与计数")
    void commitDelegatesToPipeline() {
        UUID batchId = UUID.randomUUID();
        when(importService.commit(any(), eq("en"), any(), eq("Unit 5 生词"))).thenReturn(
                new WordImportService.CommitResult(
                        new WordImportService.Counts(2, 1, 1, 0), batchId, List.of()));
        var result = endpoint.commit(new ImportTextRequest("apple, apple", "en", "Unit 5 生词", null), me);
        assertThat(result.batchId()).isEqualTo(batchId);
        assertThat(result.counts().created()).isEqualTo(1);
        assertThat(result.counts().duplicated()).isEqualTo(1);
    }
}
