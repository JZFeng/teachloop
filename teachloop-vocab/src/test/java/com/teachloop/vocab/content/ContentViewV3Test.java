package com.teachloop.vocab.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V3 卡片契约（2026-10-31 用户要求「构词与派生是最重要的信息」）：
 * - 6 个 block 固定顺序；
 * - morphology.family 必须保留 partOfSpeech / meaning，不能只留词形；
 * - etymology 独立，不和分析/构词混在一个字段；
 * - hidden 的旧 key（family/derivation/analysis）要能映射到新块。
 */
class ContentViewV3Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String V3 = """
            {
              "schemaVersion": 3,
              "word": "eager",
              "language": "en",
              "basicInfo": {
                "pronunciation": {"uk": "/ˈiːɡə/", "us": "/ˈiːɡər/"},
                "partOfSpeech": ["adjective"],
                "image": null
              },
              "meanings": {"zh": "渴望的；热切的", "en": "wanting very much"},
              "coreSemantics": {"core": "热切期盼", "notThis": "不是有兴趣", "mentalImage": "孩子盯着礼物"},
              "examples": [{"text": "She is eager to learn.", "translations": {"zh": "她渴望学习。"}}],
              "morphology": {
                "modernNote": "现代英语中 eager 是整体词，不作现代词素拆分。",
                "parts": [],
                "chain": [],
                "derivatives": [
                  {"word": "eagerly", "partOfSpeech": "adverb", "meaning": "热切地"},
                  {"word": "eagerness", "partOfSpeech": "noun", "meaning": "渴望"}
                ],
                "inflections": [],
                "family": [
                  {"word": "eager", "partOfSpeech": "adjective", "meaning": "渴望的"},
                  {"word": "eagerly", "partOfSpeech": "adverb", "meaning": "热切地"},
                  {"word": "eagerness", "partOfSpeech": "noun", "meaning": "渴望"}
                ]
              },
              "memoryAids": {
                "synonyms": [{"word": "keen", "partOfSpeech": "adjective", "nuance": "更强调兴趣浓厚"}],
                "antonyms": [{"word": "indifferent", "partOfSpeech": "adjective", "nuance": "漠不关心"}]
              },
              "etymology": {"text": "源自拉丁语 acer，经古法语 aigre 进入英语。"}
            }
            """;

    private static JsonNode view() {
        return ContentView.toView(MAPPER.readTree(V3), null, List.of());
    }

    private static java.util.List<String> ids(JsonNode view) {
        java.util.List<String> out = new java.util.ArrayList<>();
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
    @DisplayName("V3 输出固定 6 块，morphology.family 带 POS/含义，etymology 独立")
    void v3CardShape() {
        JsonNode view = view();
        assertThat(view.path("schemaVersion").asInt()).isEqualTo(3);
        assertThat(ids(view)).containsExactly(
                "senses", "morphology", "core", "examples", "synonyms", "etymology");

        JsonNode morphology = block(view, "morphology").path("payload");
        assertThat(morphology.path("modernNote").asString()).contains("整体词");
        assertThat(morphology.path("family").size()).isEqualTo(3);
        assertThat(morphology.path("family").get(1).path("partOfSpeech").asString()).isEqualTo("adverb");
        assertThat(morphology.path("family").get(1).path("meaning").asString()).isEqualTo("热切地");

        JsonNode synonyms = block(view, "synonyms").path("payload");
        assertThat(synonyms.path("items").get(0).path("word").asString()).isEqualTo("keen");
        assertThat(synonyms.path("antonyms").get(0).path("word").asString()).isEqualTo("indifferent");
        assertThat(block(view, "etymology").path("payload").path("text").asString())
                .contains("拉丁语 acer");
    }

    @Test
    @DisplayName("旧 hidden key 映射：family/derivation 隐藏 morphology，analysis 隐藏 etymology")
    void oldHiddenKeysMapToV3Modules() {
        JsonNode hiddenMorphology = ContentView.toView(MAPPER.readTree(V3), null, List.of("family"));
        assertThat(block(hiddenMorphology, "morphology")).isNull();

        JsonNode hiddenEtymology = ContentView.toView(MAPPER.readTree(V3), null, List.of("analysis"));
        assertThat(block(hiddenEtymology, "etymology")).isNull();

        JsonNode hiddenSynonyms = ContentView.toView(MAPPER.readTree(V3), null, List.of("synonyms"));
        JsonNode synonyms = block(hiddenSynonyms, "synonyms").path("payload");
        assertThat(synonyms.has("items")).isFalse();
        assertThat(synonyms.path("antonyms").get(0).path("word").asString()).isEqualTo("indifferent");
    }

    @Test
    @DisplayName("V3 的 blockOrder 不再驱动顺序；空 payload 跳过")
    void orderIsFixedAndEmptyPayloadSkipped() {
        JsonNode view = ContentView.toView(MAPPER.readTree(V3), List.of("etymology", "core"), List.of());
        assertThat(ids(view)).containsExactly(
                "senses", "morphology", "core", "examples", "synonyms", "etymology");

        JsonNode noMorphology = ContentView.toView(MAPPER.readTree("""
                {"schemaVersion": 3, "word": "x", "meanings": {"zh": "x"},
                 "basicInfo": {"partOfSpeech": ["noun"]}}
                """), null, List.of());
        assertThat(ids(noMorphology)).containsExactly("senses");
    }
}
