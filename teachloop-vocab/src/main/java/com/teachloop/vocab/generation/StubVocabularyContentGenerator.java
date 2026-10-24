package com.teachloop.vocab.generation;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 测试与本地演示用的确定性生成器（`teachloop.vocab.generation.engine=stub`）。
 *
 * <p>词形含 {@code fail} 时抛错，用于验证退避重试与 3 次失败；其余返回固定结构的合法 JSON。
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
        Map<String, Object> content = Map.of(
                "senses", List.of(Map.of("pos", "n.", "zh", "示例释义", "en", "sample sense")),
                "examples", List.of(Map.of(
                        "en", "This is a sample sentence with " + form + ".",
                        "zh", "这是包含 " + form + " 的示例句。")),
                "analysis", "stub analysis",
                "family", List.of(form),
                "phonetic", Map.of("uk", "/stʌb/", "us", "/stʌb/"));
        return mapper.writeValueAsString(content);
    }
}
