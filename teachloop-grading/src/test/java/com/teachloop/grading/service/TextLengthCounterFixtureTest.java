package com.teachloop.grading.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** T173：用 v1 的 10 份真实作文做四模型段落计数 parity。 */
class TextLengthCounterFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("T173：10 份 v1 作文在四种模型下与 Python 结果逐份一致")
    @SuppressWarnings("unchecked")
    void matchesV1PythonCounts() throws IOException {
        String expectedJson = resource("/fixtures/expected-paragraphs.json");
        Map<String, Map<String, Integer>> expected = MAPPER.readValue(expectedJson, Map.class);
        assertThat(expected).hasSize(10);

        for (Map.Entry<String, Map<String, Integer>> entry : expected.entrySet()) {
            String text = resource("/fixtures/submissions/" + entry.getKey());
            Map<String, Integer> actual = TextLengthCounter.countsByModel(text);
            assertThat(actual).as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("T072：boundary_sensitive 在 1-4 段范围内与四模型判断一致")
    void boundarySensitive() {
        assertThat(TextLengthCounter.boundarySensitive(fixture("Mark.txt"), 1, 4)).isTrue();
        assertThat(TextLengthCounter.boundarySensitive(fixture("Iam.txt"), 1, 4)).isFalse();
    }

    private static String resource(String path) throws IOException {
        try (var in = TextLengthCounterFixtureTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fixture(String name) {
        try {
            return resource("/fixtures/submissions/" + name);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
