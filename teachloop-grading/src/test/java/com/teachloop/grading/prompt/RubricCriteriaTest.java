package com.teachloop.grading.prompt;

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
 * 与 sitting 的 RubricValidatorCasesTest / 前端 web/tests 共用同一份夹具，
 * 保证「哪些行是评分项、每项多少分」三处一致（T340）。
 */
class RubricCriteriaTest {

    private static final Path FIXTURE =
            Path.of("..", "docs", "rubrics", "fixtures", "rubric-cases.json");

    @TestFactory
    List<DynamicTest> parsesSharedFixtures() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(FIXTURE));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode testCase : root.get("cases")) {
            // 无效 rubric 的分配结果不是本测试的契约（判分只处理校验能通过的 rubric）。
            if (!testCase.get("valid").asBoolean()) {
                continue;
            }
            tests.add(DynamicTest.dynamicTest(testCase.get("name").asString(), () -> {
                String rubric = rubricText(testCase);
                BigDecimal totalPoints = new BigDecimal(testCase.get("totalPoints").asString());
                JsonNode expected = testCase.get("allocated");

                List<SubjectiveGradeRequest.Criterion> criteria =
                        RubricCriteria.parse(rubric, totalPoints, "zh-CN");

                assertThat(criteria).extracting(SubjectiveGradeRequest.Criterion::name)
                        .containsExactlyElementsOf(names(expected));
                assertThat(criteria)
                        .extracting(c -> c.maxPoints().stripTrailingZeros().toPlainString())
                        .containsExactlyElementsOf(points(expected));
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
            out.add(item.get("name").asString());
        }
        return out;
    }

    private static List<String> points(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(new BigDecimal(item.get("points").asString()).stripTrailingZeros().toPlainString());
        }
        return out;
    }
}
