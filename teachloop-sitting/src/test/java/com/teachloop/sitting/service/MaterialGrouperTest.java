package com.teachloop.sitting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaterialGrouperTest {

    private static final String READING_PASSAGE = "I walked into the room twenty years after leaving high school. "
            + "The faces around me had not changed much, and the years of homework, exams and friendships came "
            + "back at once. Success, it turned out, was measured by the people who stayed.";

    private static final String PASSAGE = "I walked into the room. 20 years have gone by since I left high school, "
            + "but seeing ______ faces brought me back to my youth at once. The high school years are filled "
            + "with ______, and none of that matters years later.";

    private static Ports.ItemSpec item(int number, String stem, String options, String type) {
        return new Ports.ItemSpec(UUID.randomUUID(), number, type, "A", BigDecimal.ONE,
                stem, options, 4, "第三部分 英语知识运用");
    }

    private static Ports.ItemSpec choice(int number, String stem) {
        return item(number, stem, "A. old\nB. famous\nC. different\nD. fresh", "SINGLE_CHOICE");
    }

    private static Ports.ItemSpec fill(int number, String stem) {
        return item(number, stem, null, "FILL_BLANK");
    }

    /** 把第 index 个空标上题号，其余空保持 ______。 */
    private static String labelBlank(String text, int index, int number) {
        StringBuilder out = new StringBuilder();
        int seen = 0;
        int cursor = 0;
        while (true) {
            int start = text.indexOf("______", cursor);
            if (start < 0) {
                out.append(text.substring(cursor));
                return out.toString();
            }
            out.append(text, cursor, start);
            out.append(seen == index ? "___(" + number + ")___" : "______");
            cursor = start + 6;
            seen++;
        }
    }

    @Test
    void groupsReadingMaterialAndKeepsOnlyTheQuestionPerItem() {
        String stem21 = READING_PASSAGE + "\n\nWhat did the writer see first?";
        String stem22 = READING_PASSAGE + "\n\nWhy did the writer return?";

        var grouped = MaterialGrouper.group(List.of(choice(21, stem21), choice(22, stem22)));

        assertThat(grouped).hasSize(2);
        assertThat(grouped.get(0).groupKey()).isEqualTo("g21");
        assertThat(grouped.get(1).groupKey()).isEqualTo("g21");
        assertThat(grouped.get(0).material()).isEqualTo(READING_PASSAGE);
        assertThat(grouped.get(1).material()).isNull();
        assertThat(grouped.get(0).questionStem()).isEqualTo("What did the writer see first?");
        assertThat(grouped.get(1).questionStem()).isEqualTo("Why did the writer return?");
        assertThat(grouped.get(0).blankIndex()).isNull();
    }

    @Test
    void mapsEachClozeItemToItsBlankIndex() {
        var grouped = MaterialGrouper.group(List.of(
                choice(41, labelBlank(PASSAGE, 0, 41)),
                choice(42, labelBlank(PASSAGE, 1, 42))));

        assertThat(grouped).hasSize(2);
        assertThat(grouped.get(0).groupKey()).isEqualTo("g41");
        assertThat(grouped.get(1).groupKey()).isEqualTo("g41");
        assertThat(grouped.get(0).material()).isEqualTo(PASSAGE);
        assertThat(grouped.get(1).material()).isNull();
        assertThat(grouped.get(0).blankIndex()).isZero();
        assertThat(grouped.get(1).blankIndex()).isEqualTo(1);
    }

    /** 老快照里标记后面还跟着卷面自带的括号题号：___(41)___ (41)，展示前必须洗掉，且分组照旧。 */
    @Test
    void cleansDuplicatedNumberFromOlderClozeStems() {
        String stem41 = labelBlank(PASSAGE, 0, 41).replace("___(41)___", "___(41)___ (41)");
        String stem42 = labelBlank(PASSAGE, 1, 42).replace("___(42)___", "___(42)___ (42)");

        var grouped = MaterialGrouper.group(List.of(choice(41, stem41), choice(42, stem42)));

        assertThat(grouped).hasSize(2);
        assertThat(grouped.get(0).groupKey()).isEqualTo("g41");
        assertThat(grouped.get(0).material()).isEqualTo(PASSAGE);
        assertThat(grouped.get(1).blankIndex()).isEqualTo(1);
    }

    /** 不分组时题干整段展示，也不能把内部标记漏出去。 */
    @Test
    void soloStemHasNoInternalBlankMarkers() {
        var grouped = MaterialGrouper.group(List.of(
                fill(66, "I prefer ___(66)___ (66) (walk) to school.")));

        assertThat(grouped.get(0).questionStem()).isEqualTo("I prefer ______ (66) (walk) to school.");
    }

    @Test
    void fallsBackToSoloWhenBlankCountDoesNotMatchItemCount() {
        // 三个题共享一段只有两个空的材料：空数对不上，不能硬拆。
        var grouped = MaterialGrouper.group(List.of(
                choice(41, labelBlank(PASSAGE, 0, 41)),
                choice(42, labelBlank(PASSAGE, 1, 42)),
                choice(43, labelBlank(PASSAGE, 1, 43))));

        assertThat(grouped).extracting(MaterialGrouper.GroupedItem::groupKey).containsOnlyNulls();
    }

    @Test
    void fallsBackToSoloWhenOptionsAndFillAreMixed() {
        var grouped = MaterialGrouper.group(List.of(
                choice(41, labelBlank(PASSAGE, 0, 41)),
                fill(42, labelBlank(PASSAGE, 1, 42))));

        assertThat(grouped).extracting(MaterialGrouper.GroupedItem::groupKey).containsOnlyNulls();
    }

    @Test
    void keepsStandaloneItemsAsSolo() {
        var grouped = MaterialGrouper.group(List.of(
                fill(66, "I prefer ______ (walk) to school."),
                fill(67, "She is good at ______ (sing).")));

        assertThat(grouped).hasSize(2);
        assertThat(grouped).extracting(MaterialGrouper.GroupedItem::groupKey).containsOnlyNulls();
        assertThat(grouped).extracting(MaterialGrouper.GroupedItem::material).containsOnlyNulls();
    }

    @Test
    void leavesShortMaterialUnsplit() {
        String stem = "Short text.\n\nWhat is it?";
        var grouped = MaterialGrouper.group(List.of(choice(1, stem), choice(2, stem)));

        assertThat(grouped).extracting(MaterialGrouper.GroupedItem::groupKey).containsOnlyNulls();
    }
}
