package com.teachloop.vocab.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.content.ContentStats;
import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.content.StoredContent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T594：队列条目里的 {@code blocks} 顺序与隐藏听用户偏好。
 *
 * <p>只测 {@code toItem} 这一层（内容布局），不建库：队列取数的 SQL 在 {@link ReviewQueueStore}，
 * 与本契约无关，不为此硬造集成测试。
 */
class ReviewQueueEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String V2 = """
            {"schemaVersion": 2,
             "meanings": {"zh": "苹果"},
             "examples": [{"text": "an apple", "translations": {"zh": "一个苹果"}}],
             "memoryAids": {"synonyms": [{"word": "pome"}]},
             "basicInfo": {"image": "https://cdn.example.com/apple.png"}}
            """;

    @Test
    @DisplayName("队列条目按 prefs.blocks 排序、prefs.hidden 隐藏；statusFor 逻辑不变")
    void queueItemFollowsPrefs() {
        Store store = new Store(V2);
        // store / prefs / zones 在 toItem 里用不到，传 null 即可（构造函数不解引用它们）
        ReviewQueueEndpoint endpoint =
                new ReviewQueueEndpoint(null, store, Clock.systemUTC(), null, null, 10);
        // 存量偏好只有 8 个 key（没有 image）：normalizeBlocks 应把 image 补到末尾
        ReviewPrefs prefs = ReviewPrefs.parse(
                MAPPER.readTree("{\"blocks\":[\"examples\"],\"hidden\":[\"senses\"]}"), 20, 200);

        ReviewQueueEndpoint.QueueItem item = endpoint.toItem(dueRow(42L, "READY"), null, prefs);

        List<String> ids = ids(item.content());
        assertThat(ids.get(0)).isEqualTo("examples");
        assertThat(ids).doesNotContain("senses");
        assertThat(ids).endsWith("image");
        assertThat(item.contentStatus()).isEqualTo("READY");

        // 内容解析不到时仍然降级成 PENDING（2026-10-29 那条 Bug 的口径不能因为 T594 松动）
        ReviewQueueEndpoint.QueueItem empty = endpoint.toItem(dueRow(null, "READY"), null, prefs);
        assertThat(empty.content()).isNull();
        assertThat(empty.contentStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("默认偏好：队列条目回到 ALL_BLOCKS 顺序（9 块，image 末位）")
    void queueItemDefaultsToAllBlocks() {
        ReviewQueueEndpoint endpoint =
                new ReviewQueueEndpoint(null, new Store(V2), Clock.systemUTC(), null, null, 10);

        ReviewQueueEndpoint.QueueItem item =
                endpoint.toItem(dueRow(42L, "READY"), null, ReviewPrefs.defaults(20));

        // 默认顺序 = ALL_BLOCKS：这份内容只有 4 块有 payload，其余跳过但相对顺序不变
        assertThat(ids(item.content())).containsExactly("senses", "examples", "synonyms", "image");
    }

    private static List<String> ids(JsonNode content) {
        List<String> out = new ArrayList<>();
        for (JsonNode block : content.path("blocks")) {
            out.add(block.path("id").asString());
        }
        return out;
    }

    private static ReviewQueueStore.DueRow dueRow(Long contentId, String contentStatus) {
        return new ReviewQueueStore.DueRow(UUID.randomUUID(), "apple", "en", contentId, contentStatus,
                false, "NEW", Instant.parse("2026-10-30T00:00:00Z"), BigDecimal.ONE,
                new BigDecimal("2.5"));
    }

    /** 测试替身：只实现 toItem 需要的 getById。 */
    private static final class Store implements ContentStore {

        private final String json;

        private Store(String json) {
            this.json = json;
        }

        @Override
        public Optional<StoredContent> get(String language, String form) {
            return Optional.empty();
        }

        @Override
        public Optional<StoredContent> getById(long contentId) {
            return contentId == 42L
                    ? Optional.of(new StoredContent(42L, "en", "apple", "v2", json, "TEST"))
                    : Optional.empty();
        }

        @Override
        public long put(String language, String form, String contentJson, String source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContentStats stats() {
            return ContentStats.empty();
        }
    }
}
