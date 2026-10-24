package com.teachloop.vocab.importer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T391：预览接口把 cleanups 原样透出（行号 + 规则 + 前后值），不静默。 */
class ImportPreviewEndpointTest {

    private final ImportPreviewEndpoint endpoint = new ImportPreviewEndpoint(new DictionaryTextParser());

    @Test
    @DisplayName("粘贴文本预览：token、去重、清洗记录都在响应里")
    void previewCarriesCleanups() {
        var response = endpoint.preview(new ImportPreviewEndpoint.PreviewRequest(
                "1. \"take off\", apple n.，brave", "en"));
        assertThat(response.distinctWords()).containsExactly("take off", "apple", "brave");
        assertThat(response.cleanups()).extracting(ImportPreviewEndpoint.CleanupView::rule)
                .contains("去行首序号/项目符号", "去引号", "去词性标注", "全角标点转半角");
        assertThat(response.cleanups()).allSatisfy(cleanup -> {
            assertThat(cleanup.line()).isEqualTo(1);
            assertThat(cleanup.before()).isNotBlank();
        });
        assertThat(response.skipped()).isEmpty();
    }

    @Test
    @DisplayName("空请求返回空结果，不抛异常")
    void emptyRequest() {
        var response = endpoint.preview(null);
        assertThat(response.lineCount()).isZero();
        assertThat(response.tokens()).isEmpty();
        assertThat(response.cleanups()).isEmpty();
    }
}
