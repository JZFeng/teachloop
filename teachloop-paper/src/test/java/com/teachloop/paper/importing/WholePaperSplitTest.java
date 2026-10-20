package com.teachloop.paper.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T056：75 题整卷文本拆分（题干/选项/答案）。 */
class WholePaperSplitTest {

    @Test
    @DisplayName("75 题样卷：题号、题干、选项、答案全部拆出")
    void splitsSeventyFiveQuestions() {
        List<DocumentTextExtractor.SourceLine> lines = new ArrayList<>();
        StringBuilder answers = new StringBuilder();
        String[] keys = {"A", "B", "C", "D", "A"};
        for (int n = 1; n <= 75; n++) {
            int line = lines.size() + 1;
            lines.add(new DocumentTextExtractor.SourceLine(line, n + ". Question number " + n));
            lines.add(new DocumentTextExtractor.SourceLine(line + 1, "A. option A"));
            lines.add(new DocumentTextExtractor.SourceLine(line + 2, "B. option B"));
            lines.add(new DocumentTextExtractor.SourceLine(line + 3, "C. option C"));
            lines.add(new DocumentTextExtractor.SourceLine(line + 4, "D. option D"));
            answers.append(keys[(n - 1) % keys.length]);
        }
        lines.add(new DocumentTextExtractor.SourceLine(lines.size() + 1, "答案"));
        lines.add(new DocumentTextExtractor.SourceLine(lines.size() + 1, "1-75 " + answers));

        PaperTextParser.Result result = PaperTextParser.parse(lines);

        assertThat(result.items()).hasSize(75);
        assertThat(result.missingAnswers()).isEmpty();
        assertThat(result.items().get(0).stem()).isEqualTo("Question number 1");
        assertThat(result.items().get(0).answerKey()).isEqualTo("A");
        assertThat(result.items().get(74).answerKey()).isEqualTo(answers.substring(74, 75));
        assertThat(result.items().get(74).options()).contains("D. option D");
    }
}
