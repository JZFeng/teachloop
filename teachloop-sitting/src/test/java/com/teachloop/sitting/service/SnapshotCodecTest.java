package com.teachloop.sitting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.shared.port.Ports;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T362：叠加字段后，旧快照（没有 rubricMarkdown）仍能读出来。 */
class SnapshotCodecTest {

    private final SnapshotCodec codec = new SnapshotCodec(new tools.jackson.databind.ObjectMapper());

    @Test
    @DisplayName("旧快照没有 rubricMarkdown：按 null 读，不报错")
    void readsLegacySnapshotWithoutRubric() {
        String json = """
                {"paperId":"11111111-1111-1111-1111-111111111111","paperVersion":1,
                 "title":"旧卷","totalPoints":15,"durationMinutes":60,
                 "items":[{"itemId":"22222222-2222-2222-2222-222222222222","number":1,
                           "questionType":"ESSAY","answerKey":null,"points":15,"stem":"写一封回信。",
                           "options":null,"sectionPosition":1,"sectionTitle":"写作",
                           "scoringStrategy":"ALL_OR_NOTHING"}],
                 "rubricMarkdown":"# 评分标准\\n- 内容要点（15 分）"}
                """;

        Ports.PaperSnapshot snapshot = codec.read(json);

        assertThat(snapshot.items()).hasSize(1);
        assertThat(snapshot.items().get(0).rubricMarkdown()).isNull();
        assertThat(snapshot.rubricMarkdown()).contains("内容要点");
    }

    @Test
    @DisplayName("写出去的快照带题项 rubric，读回来一致")
    void roundTripsItemRubric() {
        Ports.PaperSnapshot snapshot = new Ports.PaperSnapshot(
                java.util.UUID.randomUUID(), 2, "新卷", 15, 60,
                java.util.List.of(new Ports.ItemSpec(java.util.UUID.randomUUID(), 15, "ESSAY", null,
                        new java.math.BigDecimal("15"), "写一封回信。", null, 1, "写作",
                        "ALL_OR_NOTHING", "# 评分标准\\n- 表达理解（5 分）")),
                "# 试卷默认");

        Ports.PaperSnapshot back = codec.read(codec.write(snapshot));

        assertThat(back.items().get(0).rubricMarkdown()).contains("表达理解");
    }
}
