package com.teachloop.vocab.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 2026-10-31：读路径补 {@code imageUrl}（113 张定稿 WebP 接入产品）。
 *
 * <p>要点：
 * <ol>
 *   <li>有图才输出 {@code imageUrl}，无图**字段整个不出现**（前端不渲染、不占位）；</li>
 *   <li>配图按 {@code (language, form)} 取——读路径命中内容包时 contentId 是 1..N 的位置号，
 *       与图片文件名（delta 的稳定 id）不是同一个 id 空间；</li>
 *   <li>{@code imageUrl} 不是第 9 块：{@code blocks} 里不能出现 image 块（T594 的块契约本任务不做）。</li>
 * </ol>
 */
class ContentViewImageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String V2 = """
            {
              "schemaVersion": 2,
              "basicInfo": {"partOfSpeech": ["noun"]},
              "meanings": {"zh": "苹果", "en": "a fruit"},
              "coreSemantics": {"core": "圆形水果", "mentalImage": "一个红苹果"}
            }
            """;

    /** 只提供一条内容 + 一张图的最小 ContentStore。 */
    private static final class Store implements ContentStore {
        private final StoredContent content;
        private final String imageUrl;

        Store(StoredContent content, String imageUrl) {
            this.content = content;
            this.imageUrl = imageUrl;
        }

        @Override
        public Optional<StoredContent> get(String language, String form) {
            return Optional.of(content);
        }

        @Override
        public Optional<StoredContent> getById(long contentId) {
            return content.id() == contentId ? Optional.of(content) : Optional.empty();
        }

        @Override
        public long put(String language, String form, String contentJson, String source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContentStats stats() {
            return new ContentStats(0, 0, 0, 0);
        }

        /** 按词形给图地址：与 {@link VocabImageStore#url(String, String)} 同一口径。 */
        @Override
        public Optional<String> imageUrl(String language, String normalizedForm) {
            return imageUrl != null && content.normalizedForm().equals(normalizedForm)
                    ? Optional.of(imageUrl) : Optional.empty();
        }
    }

    private static StoredContent content(long id, String form) {
        return new StoredContent(id, "en", form, "vocab-content-v2.5", V2, "AI");
    }

    @Test
    @DisplayName("有图：视图带 imageUrl，且 blocks 里没有 image 块")
    void imageUrlPresentAndNotABlock() {
        Store store = new Store(content(1000000022L, "apple"), "/api/vocab/contents/1000000022/image");

        JsonNode view = ContentView.resolve(store, 1000000022L, "en", "apple");

        assertThat(view.path("imageUrl").asString()).isEqualTo("/api/vocab/contents/1000000022/image");
        assertThat(view.path("coreSemantics").path("core").asString()).isEqualTo("圆形水果");
        for (JsonNode block : view.path("blocks")) {
            assertThat(block.path("id").asString()).isNotEqualTo("image");
        }
    }

    @Test
    @DisplayName("无图：字段整个不出现（不是 null、不是空串）")
    void noImageMeansNoField() {
        Store store = new Store(content(1000000022L, "apple"), null);

        JsonNode view = ContentView.resolve(store, 1000000022L, "en", "apple");

        assertThat(view.has("imageUrl")).isFalse();
        assertThat(view.path("coreSemantics").path("core").asString()).isEqualTo("圆形水果");
    }

    @Test
    @DisplayName("词条 content_id 为空（qa 现状）：按词形回退命中，仍带图")
    void formFallbackCarriesImage() {
        Store store = new Store(content(1000000022L, "apple"), "/api/vocab/contents/1000000022/image");

        JsonNode view = ContentView.resolve(store, null, "en", "apple");

        assertThat(view.path("imageUrl").asString()).isEqualTo("/api/vocab/contents/1000000022/image");
    }

    @Test
    @DisplayName("内容包命中（contentId 是 1..N 的位置号）：图片仍指向稳定的那条内容")
    void packIdContentStillGetsStableImageUrl() {
        // 包内 apple 的位置号是 7，但图片文件按 delta 的 1000000022 命名。
        Store store = new Store(content(7L, "apple"), "/api/vocab/contents/1000000022/image");

        JsonNode view = ContentView.resolve(store, 7L, "en", "apple");

        assertThat(view.path("imageUrl").asString()).isEqualTo("/api/vocab/contents/1000000022/image");
    }

    @Test
    @DisplayName("V3 内容也带 imageUrl（dev 的 prompt-version 是 v3.0）")
    void v3AlsoCarriesImageUrl() {
        String v3 = """
                {"schemaVersion": 3, "basicInfo": {"partOfSpeech": ["noun"]},
                 "meanings": {"zh": "苹果"}, "coreSemantics": {"core": "圆形水果"}}
                """;
        StoredContent stored = new StoredContent(1000000022L, "en", "apple",
                "vocab-content-v3.0", v3, "AI");
        Store store = new Store(stored, "/api/vocab/contents/1000000022/image");

        JsonNode view = ContentView.resolve(store, 1000000022L, "en", "apple");

        assertThat(view.path("schemaVersion").asInt()).isEqualTo(3);
        assertThat(view.path("imageUrl").asString()).isEqualTo("/api/vocab/contents/1000000022/image");
        for (JsonNode block : view.path("blocks")) {
            assertThat(block.path("id").asString()).isNotEqualTo("image");
        }
    }

    @Test
    @DisplayName("不传内容（还没准备）：null，不因为图片逻辑崩")
    void missingContentIsNull() {
        Store store = new Store(content(1000000022L, "apple"), "/api/vocab/contents/1000000022/image");
        assertThat(ContentView.resolve(store, null, "en", "")).isNull();
        assertThat(ContentView.resolve((ContentStore) null, 1L, "en", "apple")).isNull();
        // MAPPER 只是为了让「内容 JSON 能被解析」这件事有依据，避免常量被误删
        assertThat(MAPPER.readTree(V2).path("schemaVersion").asInt()).isEqualTo(2);
    }
}
