package com.teachloop.vocab.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.review.ReviewPrefs;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T594：v2 读路径输出**有序 blocks**（第 9 块 image），顺序/隐藏听 prefs，v1 回退不输出 blocks。
 *
 * <p>前端已按这份契约实现，字段名与形状不能漂：{@code blocks=[{id, payload}]}，
 * payload 直接复用 view 里同名字段的值。
 */
class ContentViewBlocksTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 9 块 payload 齐全的 v2 内容：默认顺序下应当一块不少、顺序等于 ALL_BLOCKS。 */
    private static final String FULL_V2 = """
            {
              "schemaVersion": 2,
              "basicInfo": {
                "partOfSpeech": ["noun"],
                "image": "https://cdn.example.com/apple.png"
              },
              "meanings": {"zh": "苹果", "en": "a fruit"},
              "examples": [
                {"text": "an apple", "translations": {"zh": "一个苹果"}, "popularity": 1}
              ],
              "wordFormation": {
                "analysis": "源自古英语 æppel",
                "parts": [{"text": "appl", "type": "root", "meaning": "苹果"}],
                "chain": [{"from": ["æppel"], "to": "apple", "partOfSpeech": "noun", "meaning": "苹果"}]
              },
              "memoryAids": {
                "synonyms": [{"word": "pome", "partOfSpeech": "noun", "nuance": "植物学说法"}],
                "antonyms": [{"word": "pear", "partOfSpeech": "noun", "nuance": "另一种水果"}]
              },
              "wordFamily": {"items": [{"word": "apples"}]},
              "coreSemantics": {"core": "可吃的果实", "notThis": "不是苹果公司", "mentalImage": "红苹果"}
            }
            """;

    private JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    private static List<String> ids(JsonNode view) {
        List<String> out = new ArrayList<>();
        for (JsonNode block : view.path("blocks")) {
            out.add(block.path("id").asString());
        }
        return out;
    }

    private static JsonNode block(JsonNode view, String id) {
        for (JsonNode block : view.path("blocks")) {
            if (id.equals(block.path("id").asString())) {
                return block;
            }
        }
        return null;
    }

    @Test
    @DisplayName("默认顺序 = ALL_BLOCKS（9 块齐全，image 在末位），payload 复用 view 里同名字段")
    void defaultOrderIsAllBlocks() {
        JsonNode view = ContentView.toView(parse(FULL_V2));

        assertThat(ids(view)).containsExactlyElementsOf(ReviewPrefs.ALL_BLOCKS);
        assertThat(ids(view)).hasSize(9).endsWith("image");

        // payload 是「原样值」：与 view 里对应字段是同一个节点，不是新造的形状
        assertThat(block(view, "core").path("payload")).isSameAs(view.path("coreSemantics"));
        assertThat(block(view, "senses").path("payload")).isSameAs(view.path("senses"));
        assertThat(block(view, "examples").path("payload")).isSameAs(view.path("examples"));
        assertThat(block(view, "synonyms").path("payload")).isSameAs(view.path("synonyms"));
        assertThat(block(view, "antonyms").path("payload")).isSameAs(view.path("antonyms"));
        assertThat(block(view, "family").path("payload")).isSameAs(view.path("family"));
        assertThat(block(view, "derivation").path("payload")).isSameAs(view.path("derivation"));
        assertThat(block(view, "analysis").path("payload")).isSameAs(view.path("analysis"));
        assertThat(block(view, "image").path("payload")).isSameAs(view.path("image"));

        // derivation 就是 {parts, chain}，analysis 是 JSON 字符串，image 是 {url}
        assertThat(block(view, "derivation").path("payload").path("parts").size()).isEqualTo(1);
        assertThat(block(view, "derivation").path("payload").path("chain").size()).isEqualTo(1);
        assertThat(block(view, "analysis").path("payload").isString()).isTrue();
        assertThat(block(view, "image").path("payload").path("url").asString())
                .isEqualTo("https://cdn.example.com/apple.png");
    }

    @Test
    @DisplayName("顺序随传入 blockOrder：[\"analysis\",\"core\",\"image\"] 之外的块不出现")
    void followsGivenOrder() {
        JsonNode view = ContentView.toView(parse(FULL_V2),
                List.of("analysis", "core", "image"), List.of());

        assertThat(ids(view)).containsExactly("analysis", "core", "image");
        // 其余块只是没被要求输出，字段本身仍在 view 里（前端按 key 回退时还能读到）
        assertThat(view.path("senses").isMissingNode()).isFalse();
        assertThat(view.path("examples").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("hidden 生效：hidden 含 senses 时 blocks 里没有 senses，但 view.senses 字段保留")
    void honoursHidden() {
        JsonNode view = ContentView.toView(parse(FULL_V2), ReviewPrefs.ALL_BLOCKS, List.of("senses"));

        assertThat(ids(view)).doesNotContain("senses");
        assertThat(ids(view)).containsExactlyElementsOf(
                ReviewPrefs.ALL_BLOCKS.stream().filter(id -> !"senses".equals(id)).toList());
        assertThat(view.path("senses").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("无图：不输出 image 字段、也没有 image 块；有图：image 块在默认顺序末位且 url 正确")
    void imageOnlyWhenSafeUrlPresent() {
        JsonNode noImage = ContentView.toView(parse("""
                {"schemaVersion": 2, "meanings": {"zh": "苹果"}}
                """));
        assertThat(noImage.path("image").isMissingNode()).isTrue();
        assertThat(ids(noImage)).doesNotContain("image");

        JsonNode withImage = ContentView.toView(parse(FULL_V2));
        assertThat(withImage.path("image").path("url").asString())
                .isEqualTo("https://cdn.example.com/apple.png");
        assertThat(ids(withImage).get(ids(withImage).size() - 1)).isEqualTo("image");

        // 不安全地址：字段与块都不出现（前端回退无图版式，而不是渲染坏图）
        JsonNode unsafe = ContentView.toView(parse("""
                {"schemaVersion": 2, "meanings": {"zh": "苹果"},
                 "basicInfo": {"image": "javascript:alert(1)"}}
                """));
        assertThat(unsafe.path("image").isMissingNode()).isTrue();
        assertThat(ids(unsafe)).doesNotContain("image");
    }

    @Test
    @DisplayName("缺 payload 跳过该块，但 blocks 数组仍然在场（v2 一块都没有也输出 []）")
    void skipsMissingPayloadButKeepsArray() {
        // 只有 schemaVersion：9 个块的 payload 一个都没有 → blocks 必须在场但为空
        JsonNode view = ContentView.toView(parse("""
                {"schemaVersion": 2, "word": "apple"}
                """));

        assertThat(view.path("blocks").isArray()).isTrue();
        assertThat(view.path("blocks").size()).isZero();
        assertThat(ids(view)).doesNotContain("analysis", "synonyms", "family", "derivation", "image");
        // 有 payload 的块才出现：senses 有值、analysis 没值，就只出 senses
        JsonNode withSenses = ContentView.toView(parse("""
                {"schemaVersion": 2, "meanings": {"zh": "释义"}}
                """), List.of("senses", "analysis"), List.of());
        assertThat(ids(withSenses)).containsExactly("senses");
    }

    @Test
    @DisplayName("未知 block id 被忽略、重复 id 去重（保留第一次的位置）")
    void ignoresUnknownAndDuplicates() {
        JsonNode view = ContentView.toView(parse(FULL_V2),
                List.of("bogus", "core", "core", "senses", "bogus"), List.of());

        assertThat(ids(view)).containsExactly("core", "senses");
    }

    @Test
    @DisplayName("v1 / 非 v2 内容原样返回（同一个实例）、不输出 blocks；null 与非法参数安全")
    void v1KeepsRawWithoutBlocks() {
        JsonNode v1 = parse("""
                {"senses":[{"pos":"n.","zh":"苹果"}],"analysis":"旧内容"}
                """);
        assertThat(ContentView.toView(v1)).isSameAs(v1);
        assertThat(ContentView.toView(v1, ReviewPrefs.ALL_BLOCKS, List.of())).isSameAs(v1);
        assertThat(v1.has("blocks")).isFalse();

        JsonNode other = parse("{\"schemaVersion\": 4, \"senses\": []}");
        assertThat(ContentView.toView(other, List.of("core"), List.of())).isSameAs(other);

        JsonNode arrayRaw = parse("[1,2,3]");
        assertThat(ContentView.toView(arrayRaw, ReviewPrefs.ALL_BLOCKS, List.of())).isSameAs(arrayRaw);

        assertThat(ContentView.toView(null, ReviewPrefs.ALL_BLOCKS, List.of())).isNull();
        assertThat(ContentView.toView(null)).isNull();
        // blockOrder / hidden 为 null：按默认顺序、不隐藏
        JsonNode defaults = ContentView.toView(parse(FULL_V2), null, null);
        assertThat(ids(defaults)).containsExactlyElementsOf(ReviewPrefs.ALL_BLOCKS);
    }

    @Test
    @DisplayName("图片地址白名单：http(s) 绝对地址与站内相对路径放行，协议相对/脚本/数据地址拒绝")
    void imageUrlWhitelist() {
        assertThat(ContentView.isSafeImageUrl("https://cdn.example.com/a.png")).isTrue();
        assertThat(ContentView.isSafeImageUrl("http://cdn.example.com/a.png")).isTrue();
        assertThat(ContentView.isSafeImageUrl("/uploads/apple.png")).isTrue();
        assertThat(ContentView.isSafeImageUrl("  https://cdn.example.com/a.png  ")).isTrue();

        assertThat(ContentView.isSafeImageUrl("//evil.example.com/a.png")).isFalse();
        assertThat(ContentView.isSafeImageUrl("javascript:alert(1)")).isFalse();
        assertThat(ContentView.isSafeImageUrl("data:image/png;base64,AAAA")).isFalse();
        assertThat(ContentView.isSafeImageUrl("ftp://cdn.example.com/a.png")).isFalse();
        assertThat(ContentView.isSafeImageUrl("https://")).isFalse();
        assertThat(ContentView.isSafeImageUrl("https:///a.png")).isFalse();
        assertThat(ContentView.isSafeImageUrl("uploads/apple.png")).isFalse();
        assertThat(ContentView.isSafeImageUrl(" ")).isFalse();
        assertThat(ContentView.isSafeImageUrl(null)).isFalse();
    }

    @Test
    @DisplayName("resolve 带 prefs 布局：顺序/隐藏生效，取不到时回退语义与单参版本一致")
    void resolveAppliesLayout() {
        Store store = new Store();
        store.json = FULL_V2;

        JsonNode view = ContentView.resolve(store, 42L, "en", "apple",
                List.of("examples", "image"), List.of("image"));
        assertThat(ids(view)).containsExactly("examples");

        JsonNode byForm = ContentView.resolve(store, null, "en", "apple",
                List.of("core"), List.of());
        assertThat(ids(byForm)).containsExactly("core");

        assertThat(ContentView.resolve(store, null, "en", "apple", null, null).path("blocks").size())
                .isEqualTo(9);
        assertThat(ContentView.resolve(store, 999L, "en", "missing", List.of("core"), List.of()))
                .isNull();
        assertThat(ContentView.resolve(null, 42L, "en", "apple", List.of("core"), List.of())).isNull();
    }

    /** 测试替身：只实现 resolve 需要的 getById / get。 */
    private static final class Store implements ContentStore {

        private String json;

        @Override
        public Optional<StoredContent> get(String language, String form) {
            return "apple".equals(form)
                    ? Optional.of(new StoredContent(42L, "en", "apple", "v2", json, "TEST"))
                    : Optional.empty();
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
