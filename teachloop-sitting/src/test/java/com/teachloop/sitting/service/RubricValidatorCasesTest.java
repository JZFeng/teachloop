package com.teachloop.sitting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 共享夹具（docs/rubrics/fixtures/rubric-cases.json）：同一组 rubric 同时被
 * 校验（这里）、判分（teachloop-grading 的 RubricCriteriaTest）与前端（web/tests）读。
 *
 * <p>T340 起，分组标题/说明行这类「没有分值」的条目不再算评分项，而是进 ignored：
 * 只用于界面提示，不阻断发布；评分项的选择规则见 RubricValidator.parse。
 */
class RubricValidatorCasesTest {

    private static final Path FIXTURE =
            Path.of("..", "docs", "rubrics", "fixtures", "rubric-cases.json");

    @TestFactory
    List<DynamicTest> validatesSharedFixtures() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(FIXTURE));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode testCase : root.get("cases")) {
            tests.add(DynamicTest.dynamicTest(testCase.get("name").asString(), () -> {
                String rubric = rubricText(testCase);
                BigDecimal totalPoints = new BigDecimal(testCase.get("totalPoints").asString());
                JsonNode expectedCriteria = testCase.get("allocated");

                RubricValidator.Result result = RubricValidator.validate(rubric, totalPoints);

                assertThat(result.valid()).isEqualTo(testCase.get("valid").asBoolean());
                assertThat(result.criteria()).extracting(RubricValidator.Criterion::name)
                        .containsExactlyElementsOf(names(expectedCriteria));
                assertThat(result.criteria())
                        .extracting(c -> c.maxPoints().stripTrailingZeros().toPlainString())
                        .containsExactlyElementsOf(points(expectedCriteria));
                assertThat(result.total()).isEqualByComparingTo(sum(expectedCriteria));
                assertThat(result.ignored()).containsExactlyElementsOf(names(testCase.get("ignored")));
                assertThat(result.issues()).extracting(RubricValidator.Issue::code)
                        .containsExactlyElementsOf(names(testCase.get("issues")));
            }));
        }
        return tests;
    }

    private static String rubricText(JsonNode testCase) throws Exception {
        if (testCase.has("file")) {
            return Files.readString(Path.of("..", testCase.get("file").asString()));
        }
        return testCase.get("rubric").asString();
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(item.isString() ? item.asString() : item.get("name").asString());
        }
        return out;
    }

    private static List<String> points(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(new BigDecimal(item.get("points").asString())
                    .stripTrailingZeros().toPlainString());
        }
        return out;
    }

    private static BigDecimal sum(JsonNode array) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode item : array) {
            total = total.add(new BigDecimal(item.get("points").asString()));
        }
        return total;
    }
}
