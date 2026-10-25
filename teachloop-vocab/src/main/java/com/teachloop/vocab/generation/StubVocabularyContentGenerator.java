package com.teachloop.vocab.generation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 测试与本地演示用的确定性生成器（`teachloop.vocab.generation.engine=stub`）。
 *
 * <p>输出与 AI 生成同样走 v2 结构（AC-60.9），这样 stub 模式下的读路径、翻面明细、
 * 例句排序也走真实映射，而不是另一套形状。词形含 {@code fail} 时抛错，用于验证退避重试。
 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.generation.engine", havingValue = "stub")
public class StubVocabularyContentGenerator implements VocabularyContentGenerator {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String generate(String form, String language) {
        if (form.contains("fail")) {
            throw new ContentGenerationException("stub failure for " + form);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schemaVersion", 2);
        content.put("word", form);
        content.put("language", language);
        content.put("basicInfo", Map.of(
                "pronunciation", Map.of("uk", "/stʌb/", "us", "/stʌb/"),
                "partOfSpeech", List.of("noun")));
        content.put("meanings", Map.of(
                "zh", "示例释义（stub）",
                "en", "sample sense (stub)"));
        content.put("examples", List.of(
                example("This is a sample sentence with " + form + ".", "这是包含 " + form + " 的示例句。", 1),
                example("Another sentence uses " + form + " again.", "另一句又用了 " + form + "。", 2)));
        content.put("wordFormation", Map.of(
                "analysis", "stub analysis",
                "parts", List.of(Map.of("text", form, "type", "root", "meaning", "stub"))));
        content.put("memoryAids", Map.of(
                "synonyms", List.of(),
                "antonyms", List.of(),
                "mnemonic", "stub mnemonic",
                "supplement", ""));
        content.put("wordFamily", Map.of("items", List.of(
                Map.of("word", form + "s",
                        "partOfSpeech", List.of("noun"),
                        "meanings", Map.of("zh", "复数（stub）", "en", "plural (stub)")))));
        return mapper.writeValueAsString(content);
    }

    private static Map<String, Object> example(String text, String zh, int popularity) {
        return Map.of(
                "text", text,
                "translations", Map.of("zh", zh),
                "partOfSpeech", "noun",
                "usagePattern", "stub usage",
                "popularity", popularity);
    }
}
