package com.teachloop.grading.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.teachloop.grading.domain.AiCallRepository;
import com.teachloop.grading.engine.AiGradingEngine;
import com.teachloop.grading.prompt.PromptBuilder;
import com.teachloop.grading.prompt.SubjectiveGradeRequest;
import com.teachloop.grading.service.TextLengthCounter;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T265：用真实 DeepSeek 对 10 份人工批改样本跑 subjective-v2，输出逐项对比报告。
 *
 * <p>默认 {@code mvn test} 跳过（{@code @Tag("ai")}）。手工运行：
 * <pre>
 * DEEPSEEK_API_KEY=... ./mvnw -pl teachloop-grading -am -Dgroups=ai -Dtest=GradingQualityEvalTest test
 * </pre>
 * 报告写到 {@code docs/reports/grading-quality/2026-10-21-subjective-v2.md}。
 */
@Tag("ai")
class GradingQualityEvalTest {

    private static final Pattern CRITERION = Pattern.compile("\\n\\d\\. ([^:]+): (\\d+)/(\\d+)");
    private static final Path SAMPLES = Path.of("..", "docs", "rubrics", "samples");

    @Test
    @DisplayName("10 份 golden set：逐项一致率、总分偏差、契约与语言合规")
    void evaluateGoldenSet() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY 未配置，跳过真机评估");
        String rubric = Files.readString(Path.of("..", "docs", "rubrics", "Rubric.md"));
        String instructions = Files.readString(Path.of("..", "docs", "rubrics", "Grading_Prompt.md"));
        DeepSeekLlmClient client = new DeepSeekLlmClient(apiKey, "https://api.deepseek.com", "deepseek-flash",
                new BigDecimal("0.2"), Duration.ofSeconds(180));
        AiGradingEngine engine = new AiGradingEngine(new PromptBuilder(), new GradeOutputValidator(),
                client, mock(AiCallRepository.class), Clock.fixed(Instant.now(), ZoneOffset.UTC));

        List<Row> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try (var files = Files.list(SAMPLES.resolve("submissions"))) {
            for (Path submission : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                String name = submission.getFileName().toString().replace(".txt", "");
                String text = Files.readString(submission);
                Map<String, Integer> human = humanScores(SAMPLES.resolve("reviews").resolve(name + "_review.txt"));
                int humanTotal = human.values().stream().mapToInt(Integer::intValue).sum();
                boolean boundary = TextLengthCounter.boundarySensitive(text, 1, 4);
                Ports.GradingResult result;
                try {
                    result = engine.grade(workItem(rubric, instructions, text));
                } catch (RuntimeException ex) {
                    failures.add(name + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    continue;
                }
                Map<String, Integer> ai = new LinkedHashMap<>();
                for (Ports.CriterionScore score : result.criteria()) {
                    ai.put(score.name(), score.points().intValue());
                }
                rows.add(new Row(name, human, ai, humanTotal, result.subjectivePoints().intValue(),
                        boundary, result.feedback()));
            }
        }

        int exactCriteria = 0;
        int totalCriteria = 0;
        int totalMatches = 0;
        int nonBoundary = 0;
        int language = 0;
        for (Row row : rows) {
            for (String name : row.human().keySet()) {
                totalCriteria++;
                if (row.human().get(name).equals(row.ai().get(name))) {
                    exactCriteria++;
                }
            }
            if (row.humanTotal() == row.aiTotal()) {
                totalMatches++;
            }
            if (!row.boundary()) {
                nonBoundary++;
            }
            if (row.feedback() != null && row.feedback().contains("Overall:")) {
                language++;
            }
        }
        writeReport(rows, failures, exactCriteria, totalCriteria, totalMatches, nonBoundary, language);
        assertThat(failures).as("契约失败：%s", failures).isEmpty();
        assertThat(rows).hasSize(10);
    }

    private static Map<String, Integer> humanScores(Path review) throws Exception {
        Map<String, Integer> scores = new LinkedHashMap<>();
        Matcher m = CRITERION.matcher("\n" + Files.readString(review));
        while (m.find()) {
            scores.put(m.group(1).strip(), Integer.parseInt(m.group(2)));
        }
        return scores;
    }

    private static Ports.WorkItem workItem(String rubric, String instructions, String text) {
        return new Ports.WorkItem(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "", "TEXT", text, null, List.of(),
                List.of(new Ports.ItemSpec(java.util.UUID.randomUUID(), 1, "ESSAY", null,
                        new BigDecimal("10"), instructions)),
                rubric, null, "en");
    }

    private static void writeReport(List<Row> rows, List<String> failures, int exact, int total,
                                    int totalMatches, int nonBoundary, int language) throws Exception {
        StringBuilder sb = new StringBuilder("# subjective-v2 质量评估（2026-10-21）\n\n");
        sb.append("模型：deepseek-flash；样本：`docs/rubrics/samples` 10 份；语言：en；prompt：subjective-v2。\n\n");
        sb.append("## 汇总\n\n");
        sb.append("- 逐项 exact：" + exact + "/" + total + "\n");
        sb.append("- 总分 exact：" + totalMatches + "/" + rows.size() + "\n");
        sb.append("- 非边界样本：" + nonBoundary + "/" + rows.size() + "\n");
        sb.append("- 契约失败：" + failures.size() + "\n");
        sb.append("- 语言合规（英文）：" + language + "/" + rows.size() + "\n\n");
        if (!failures.isEmpty()) {
            sb.append("## 契约失败\n\n");
            failures.forEach(f -> sb.append("- " + f + "\n"));
            sb.append("\n");
        }
        sb.append("## 逐份\n\n| 学生 | 人工 | AI | 逐项 | 边界 |\n| :--- | ---: | ---: | :--- | :--- |\n");
        for (Row row : rows) {
            sb.append("| " + row.name() + " | " + row.humanTotal() + " | " + row.aiTotal() + " | "
                    + row.human() + " → " + row.ai() + " | " + (row.boundary() ? "是" : "否") + " |\n");
        }
        Path dir = Path.of("..", "docs", "reports", "grading-quality");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("2026-10-21-subjective-v2.md"), sb.toString());
    }

    private record Row(String name, Map<String, Integer> human, Map<String, Integer> ai,
                       int humanTotal, int aiTotal, boolean boundary, String feedback) {
    }
}
