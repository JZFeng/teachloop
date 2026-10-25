package com.teachloop.vocab.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** T431：v2 内容（内容包/delta 的格式）适配成前端当前 v1 形状；v1 原样返回。 */
class ContentViewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("v2 → v1：senses/examples/analysis/family/phonetic 全部映射，字段值来自 v2 对应位置")
    void mapsSchemaV2() {
        JsonNode view = ContentView.toView(parse("""
                {
                  "schemaVersion": 2,
                  "word": "struggle",
                  "language": "en",
                  "basicInfo": {
                    "pronunciation": {"uk": "/ˈstrʌɡ(ə)l/", "us": "/ˈstrʌɡəl/"},
                    "partOfSpeech": ["verb", "noun"]
                  },
                  "meanings": {"zh": "奋斗；挣扎", "en": "to try very hard"},
                  "examples": [
                    {"text": "She struggled to open the jar.", "translations": {"zh": "她费力地想打开罐子。"},
                     "popularity": 2},
                    {"text": "He struggled with math.", "translations": {"zh": "他数学学得很吃力。"},
                     "popularity": 1}
                  ],
                  "wordFormation": {"analysis": "源自古法语 estrangler", "parts": []},
                  "memoryAids": {"supplement": "补充说明", "mnemonic": "口诀"},
                  "wordFamily": {"items": [
                    {"word": "struggled"}, {"word": "struggling"}, {"word": "struggled"}
                  ]}
                }
                """));

        assertThat(view.path("senses").size()).isEqualTo(1);
        JsonNode sense = view.path("senses").get(0);
        assertThat(sense.path("pos").asString()).isEqualTo("verb, noun");
        assertThat(sense.path("zh").asString()).isEqualTo("奋斗；挣扎");
        assertThat(sense.path("en").asString()).isEqualTo("to try very hard");

        // popularity 升序（1 在前）；翻译与正文分列映射成 en / zh
        assertThat(view.path("examples").size()).isEqualTo(2);
        assertThat(view.path("examples").get(0).path("en").asString())
                .isEqualTo("He struggled with math.");
        assertThat(view.path("examples").get(0).path("zh").asString()).isEqualTo("他数学学得很吃力。");
        assertThat(view.path("examples").get(1).path("en").asString())
                .isEqualTo("She struggled to open the jar.");

        assertThat(view.path("analysis").asString()).isEqualTo("源自古法语 estrangler");
        assertThat(view.path("family").size()).isEqualTo(2);
        assertThat(view.path("family").get(0).asString()).isEqualTo("struggled");
        assertThat(view.path("family").get(1).asString()).isEqualTo("struggling");
        assertThat(view.path("phonetic").path("uk").asString()).isEqualTo("/ˈstrʌɡ(ə)l/");
        assertThat(view.path("phonetic").path("us").asString()).isEqualTo("/ˈstrʌɡəl/");
    }

    @Test
    @DisplayName("v2 缺 popularity 的例句排在末尾且彼此保持原序；缺字段时省略而不是输出空壳")
    void handlesMissingFields() {
        JsonNode view = ContentView.toView(parse("""
                {
                  "schemaVersion": 2,
                  "meanings": {"zh": "释义"},
                  "examples": [
                    {"text": "no popularity one"},
                    {"text": "popular two", "popularity": 2},
                    {"text": "no popularity two"},
                    {"text": "popular one", "popularity": 1}
                  ]
                }
                """));

        assertThat(view.path("examples").get(0).path("en").asString()).isEqualTo("popular one");
        assertThat(view.path("examples").get(1).path("en").asString()).isEqualTo("popular two");
        assertThat(view.path("examples").get(2).path("en").asString()).isEqualTo("no popularity one");
        assertThat(view.path("examples").get(3).path("en").asString()).isEqualTo("no popularity two");

        assertThat(view.path("senses").size()).isEqualTo(1);
        assertThat(view.path("senses").get(0).path("zh").asString()).isEqualTo("释义");
        assertThat(view.path("analysis").isMissingNode()).isTrue();
        assertThat(view.path("family").isMissingNode()).isTrue();
        assertThat(view.path("phonetic").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("v2 用 memoryAids.supplement 兜底 analysis；两者都没有时才省略")
    void fallsBackToSupplement() {
        JsonNode view = ContentView.toView(parse("""
                {"schemaVersion": 2, "memoryAids": {"supplement": "常见搭配是 struggle to do"}}
                """));
        assertThat(view.path("analysis").asString()).isEqualTo("常见搭配是 struggle to do");
    }

    @Test
    @DisplayName("v1（无 schemaVersion 或不是 2）原样返回：存量 AI 内容不被改写")
    void keepsSchemaV1Untouched() {
        JsonNode v1 = parse("""
                {"senses":[{"pos":"n.","zh":"苹果"}],"examples":[{"en":"an apple","zh":"一个苹果"}],
                 "analysis":"旧内容","family":["apples"],"phonetic":{"uk":"/ˈæpl/","us":"/ˈæpl/"}}
                """);
        assertThat(ContentView.toView(v1)).isSameAs(v1);

        JsonNode other = parse("""
                {"schemaVersion": 3, "senses": []}
                """);
        assertThat(ContentView.toView(other)).isSameAs(other);
        assertThat(ContentView.toView(null)).isNull();
    }

    @Test
    @DisplayName("按 content_id 解析：命中返回 v1 视图；null / 未命中 / 非法 JSON 都返回 null 不抛")
    void resolvesByContentId() {
        InMemoryStore store = new InMemoryStore();
        store.json = "{\"schemaVersion\":2,\"meanings\":{\"zh\":\" kettle 水壶\"}}";

        JsonNode view = ContentView.resolve(store, 42L);
        assertThat(view.path("senses").get(0).path("zh").asString()).isEqualTo("kettle 水壶");

        assertThat(ContentView.resolve(store, null)).isNull();
        assertThat(ContentView.resolve(store, 0L)).isNull();
        assertThat(ContentView.resolve(store, 999L)).isNull();
        store.json = "not-json";
        assertThat(ContentView.resolve(store, 42L)).isNull();
    }

    /** 测试替身：只实现 resolve 需要的 getById。 */
    private static final class InMemoryStore implements ContentStore {

        private String json;

        @Override
        public Optional<StoredContent> get(String language, String form) {
            return Optional.empty();
        }

        @Override
        public Optional<StoredContent> getById(long contentId) {
            return json == null || contentId != 42L
                    ? Optional.empty()
                    : Optional.of(new StoredContent(contentId, "en", "kettle", "v1", json, "TEST"));
        }

        @Override
        public long put(String language, String form, String contentJson, String source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContentStats stats() {
            return new ContentStats(0, 0, 0, 0);
        }
    }
}
