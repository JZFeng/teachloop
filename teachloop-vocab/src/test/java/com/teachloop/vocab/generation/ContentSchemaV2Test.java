package com.teachloop.vocab.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** AC-60.9 / AC-60.12：v2 结构校验，噪音就地清洗，硬错误才退回重试。 */
class ContentSchemaV2Test {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode sample(String word, int examples) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 2);
        root.put("word", word);
        root.put("language", "en");

        ObjectNode basic = root.putObject("basicInfo");
        ObjectNode pronunciation = basic.putObject("pronunciation");
        pronunciation.put("uk", "/ˈæp.əl/");
        pronunciation.put("us", "/ˈæp.əl/");
        basic.putArray("partOfSpeech").add("noun");

        ObjectNode meanings = root.putObject("meanings");
        meanings.put("zh", "苹果");
        meanings.put("en", "a round fruit");

        ArrayNode ex = root.putArray("examples");
        for (int index = 1; index <= examples; index++) {
            ObjectNode example = ex.addObject();
            example.put("text", "Sentence " + index + " uses " + word + ".");
            example.putObject("translations").put("zh", "例句 " + index);
            example.put("partOfSpeech", "noun");
            example.put("usagePattern", "use " + word);
            example.put("popularity", index);
        }

        ObjectNode formation = root.putObject("wordFormation");
        formation.put("analysis", "From Old English.");
        formation.putArray("parts").addObject()
                .put("text", word).put("type", "root").put("meaning", "fruit");
        formation.put("confidence", "high");

        ObjectNode family = root.putObject("wordFamily");
        ObjectNode item = family.putArray("items").addObject();
        item.put("word", word + "s");
        item.putArray("partOfSpeech").add("noun");
        item.putObject("meanings").put("zh", "复数").put("en", "plural");
        return root;
    }

    @Test
    void cleanRecordPasses() {
        assertThat(ContentSchemaV2.validateAndClean(sample("apple", 6), "apple", "en", 1, 6)).isEmpty();
    }

    @Test
    void exampleWithoutTargetWordIsDroppedAndPopularityRenumbered() {
        ObjectNode content = sample("apple", 3);
        ArrayNode examples = (ArrayNode) content.path("examples");
        ((ObjectNode) examples.get(1)).put("text", "Rain fell all night.");
        ((ObjectNode) examples.get(1)).put("usagePattern", "rain falls");

        assertThat(ContentSchemaV2.validateAndClean(content, "apple", "en", 1, 6)).isEmpty();
        assertThat(content.path("examples")).hasSize(2);
        assertThat(content.path("examples").get(0).path("popularity").asInt()).isEqualTo(1);
        assertThat(content.path("examples").get(1).path("popularity").asInt()).isEqualTo(2);
    }

    @Test
    void duplicateSentencesAreDropped() {
        ObjectNode content = sample("apple", 2);
        ArrayNode examples = (ArrayNode) content.path("examples");
        ((ObjectNode) examples.get(1)).put("text", "Sentence 1 uses apple.");

        assertThat(ContentSchemaV2.validateAndClean(content, "apple", "en", 1, 6)).isEmpty();
        assertThat(content.path("examples")).hasSize(1);
    }

    @Test
    void noUsableExampleIsAHardProblem() {
        ObjectNode content = sample("apple", 2);
        ArrayNode examples = (ArrayNode) content.path("examples");
        ((ObjectNode) examples.get(0)).put("text", "Rain fell.");
        ((ObjectNode) examples.get(0)).put("usagePattern", "rain");
        ((ObjectNode) examples.get(1)).put("text", "Snow fell.");
        ((ObjectNode) examples.get(1)).put("usagePattern", "snow");

        List<String> problems = ContentSchemaV2.validateAndClean(content, "apple", "en", 1, 6);
        assertThat(ContentSchemaV2.join(problems)).contains("no sentence contains the target word");
    }

    @Test
    void irregularInflectionsCountAsTheWord() {
        ObjectNode content = sample("leaf", 1);
        ((ObjectNode) content.path("examples").get(0)).put("text", "The leaves turn red in autumn.");
        assertThat(ContentSchemaV2.validateAndClean(content, "leaf", "en", 1, 6)).isEmpty();
    }

    @Test
    void wordFamilySelfReferenceAndDuplicatesAreCleaned() {
        ObjectNode content = sample("apple", 1);
        ObjectNode family = content.putObject("wordFamily");
        ArrayNode items = family.putArray("items");
        items.addObject().put("word", "apple").putArray("partOfSpeech").add("noun");
        items.addObject().put("word", "apples").putArray("partOfSpeech").add("noun");
        items.addObject().put("word", "apples").putArray("partOfSpeech").add("noun");
        items.addObject().put("word", "applesauce").putArray("partOfSpeech").add("noun");
        for (int i = 0; i < items.size(); i++) {
            ((ObjectNode) items.get(i)).putObject("meanings").put("zh", "释义").put("en", "meaning");
        }

        assertThat(ContentSchemaV2.validateAndClean(content, "apple", "en", 1, 6)).isEmpty();
        ArrayNode cleaned = (ArrayNode) content.path("wordFamily").path("items");
        assertThat(cleaned).hasSize(2);
        assertThat(cleaned.get(0).path("word").asString()).isEqualTo("apples");
        assertThat(cleaned.get(1).path("word").asString()).isEqualTo("applesauce");
    }

    @Test
    void incompleteFormationPartIsDropped() {
        ObjectNode content = sample("apple", 1);
        ObjectNode formation = (ObjectNode) content.path("wordFormation");
        ArrayNode parts = formation.putArray("parts");
        parts.addObject().put("text", "apple").put("type", "root").put("meaning", "fruit");
        parts.addObject().put("text", "ap").put("type", "prefix");

        assertThat(ContentSchemaV2.validateAndClean(content, "apple", "en", 1, 6)).isEmpty();
        assertThat(content.path("wordFormation").path("parts")).hasSize(1);
    }

    @Test
    void hardErrorsStillFail() {
        ObjectNode missingEnglish = sample("apple", 6);
        ((ObjectNode) missingEnglish.path("meanings")).put("en", "");
        assertThat(ContentSchemaV2.join(
                ContentSchemaV2.validateAndClean(missingEnglish, "apple", "en", 1, 6)))
                .contains("meanings.en empty");

        ObjectNode wrongLanguage = sample("apple", 6);
        wrongLanguage.put("language", "es");
        assertThat(ContentSchemaV2.join(
                ContentSchemaV2.validateAndClean(wrongLanguage, "apple", "en", 1, 6)))
                .contains("language mismatch");

        ObjectNode missingZh = sample("apple", 6);
        ObjectNode firstExample = (ObjectNode) missingZh.path("examples").get(0);
        ((ObjectNode) firstExample.path("translations")).put("zh", "");
        assertThat(ContentSchemaV2.join(
                ContentSchemaV2.validateAndClean(missingZh, "apple", "en", 1, 6)))
                .contains("translations.zh empty");
    }

    @Test
    void wordMatchingUsesBoundaries() {
        assertThat(ContentSchemaV2.containsForm("This sample lacks it.", ContentSchemaV2.formsOf("is")))
                .isFalse();
        assertThat(ContentSchemaV2.containsForm("This is fine.", ContentSchemaV2.formsOf("is")))
                .isTrue();
        assertThat(ContentSchemaV2.containsForm("They keep chickens in the backyard.",
                ContentSchemaV2.formsOf("yard"))).isFalse();
        assertThat(ContentSchemaV2.formsOf("leaf")).contains("leaves");
        assertThat(ContentSchemaV2.formsOf("leave")).contains("left");
    }
}
