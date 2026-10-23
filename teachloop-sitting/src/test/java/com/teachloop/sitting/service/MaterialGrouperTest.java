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
    void fallsBackToSoloWhenThereAreMoreItemsThanBlanks() {
        // 三个题共享一段只有两个空的材料：空放不下三道题，不能硬拆。
        var grouped = MaterialGrouper.group(List.of(
                choice(41, labelBlank(PASSAGE, 0, 41)),
                choice(42, labelBlank(PASSAGE, 1, 42)),
                choice(43, labelBlank(PASSAGE, 1, 43))));

        assertThat(grouped).extracting(MaterialGrouper.GroupedItem::groupKey).containsOnlyNulls();
    }

    @Test
    void fallsBackToSoloWhenTwoItemsClaimTheSameBlank() {
        // 两个空、两道题都指着第 2 个空：一个空不能放两道题。
        var grouped = MaterialGrouper.group(List.of(
                choice(41, relabel(PASSAGE, 1, 41)),
                choice(42, relabel(PASSAGE, 1, 42))));

        assertThat(grouped).extracting(MaterialGrouper.GroupedItem::groupKey).containsOnlyNulls();
    }

    /** 把第 index 个空整段换成题号标签（不吃后面的括号），其余空原样保留卷面提示。 */
    private static String relabel(String text, int index, int number) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("_{3,}").matcher(text);
        StringBuilder out = new StringBuilder();
        int seen = 0;
        while (m.find()) {
            String replacement = seen == index ? "___(" + number + ")___" : m.group();
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
            seen++;
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---- T355：空数多于题数、兄弟题的题号提示口径不一致，都不能再退化成逐题重复渲染 ----

    /** 七选五原文有 5 个空，本卷只导入了第 7、8 两道题：材料只渲染一次，控件落在第 1、2 个空。 */
    @Test
    void groupsPassageWithMoreBlanksThanQuestions() {
        String material = "How can English be learned well? In my view, keeping up with teachers is a good way. "
                + "Teachers are experienced and their plans are effective. But remember to work in a way that "
                + "suits you. ______ Keep a notebook to make a record of the mistakes you make.\n"
                + "The school life may be hard, but you're not alone. You may be busy studying, but you can "
                + "show your care by calling your friends or sending short messages. ______\n"
                + "It's as important to live well as it is to study well. Going to bed no later than 11:30 pm "
                + "is important. ______ You needn't follow a special diet.\n"
                + "______ Having a lot of schoolwork doesn't mean having to quit other activities.";
        String stem7 = relabel(material, 0, 7);
        String stem8 = relabel(material, 1, 8);

        var grouped = MaterialGrouper.group(List.of(
                choice(7, stem7), choice(8, stem8)));

        assertThat(grouped).hasSize(2);
        assertThat(grouped.get(0).groupKey()).isEqualTo("g7");
        assertThat(grouped.get(1).groupKey()).isEqualTo("g7");
        assertThat(grouped.get(0).material()).isEqualTo(material);
        assertThat(grouped.get(1).material()).isNull();
        assertThat(grouped.get(0).blankIndex()).isZero();
        assertThat(grouped.get(1).blankIndex()).isEqualTo(1);
    }

    /** 完形：同一段材料里，9 号题的题干把 10 号空写成「______ (10)」，10 号题的题干把 9 号空写成「______ (9)」。 */
    @Test
    void groupsClozeWhenSiblingQuestionsKeepTheirNumberHints() {
        String stem9 = "I walked into the room. 20 years have gone by since I left high school, but seeing "
                + "___(9)___ faces brought me back to my youth at once.\n\"Dreamlike\" is the best word to "
                + "______ (10) my 20-year high school reunion.";
        String stem10 = "I walked into the room. 20 years have gone by since I left high school, but seeing "
                + "______ (9) faces brought me back to my youth at once.\n\"Dreamlike\" is the best word to "
                + "___(10)___ my 20-year high school reunion.";
        String material = "I walked into the room. 20 years have gone by since I left high school, but seeing "
                + "______ faces brought me back to my youth at once.\n\"Dreamlike\" is the best word to "
                + "______ my 20-year high school reunion.";

        var grouped = MaterialGrouper.group(List.of(choice(9, stem9), choice(10, stem10)));

        assertThat(grouped).hasSize(2);
        assertThat(grouped).extracting(MaterialGrouper.GroupedItem::groupKey).containsOnly("g9");
        assertThat(grouped.get(0).material()).isEqualTo(material);
        assertThat(grouped.get(1).blankIndex()).isEqualTo(1);
    }

    /** 语法填空：括号里是「(challenge)」这类提示词，不是题号，不能被当成空位题号洗掉。 */
    @Test
    void groupsGrammarFillWithoutEatingWordHints() {
        String stem11 = "Entering senior high school is an exciting but ___(11)___ (challenge) experience for many "
                + "teenagers. On the first day, I felt a mix of ______ (12) (anxious) and excitement.";
        String stem12 = "Entering senior high school is an exciting but ______ (11) (challenge) experience for many "
                + "teenagers. On the first day, I felt a mix of ___(12)___ (anxious) and excitement.";
        String material = "Entering senior high school is an exciting but ______ (challenge) experience for many "
                + "teenagers. On the first day, I felt a mix of ______ (anxious) and excitement.";

        var grouped = MaterialGrouper.group(List.of(fill(11, stem11), fill(12, stem12)));

        assertThat(grouped).hasSize(2);
        assertThat(grouped).extracting(MaterialGrouper.GroupedItem::groupKey).containsOnly("g11");
        assertThat(grouped.get(0).material()).isEqualTo(material);
        assertThat(grouped.get(1).blankIndex()).isEqualTo(1);
    }

    /** 正文里的数字括号（年份、价格）不是空位题号，不能被当提示洗掉。 */
    @Test
    void keepsNumericParenthesesThatAreNotQuestionNumbers() {
        String stem = "The museum was built ______ (2019) and the ticket was ______ (5).";
        var grouped = MaterialGrouper.group(List.of(
                fill(60, stem), fill(61, stem)));

        // 没有题号标记 → 两道题都不属于空组，保持原样。
        assertThat(grouped).extracting(MaterialGrouper.GroupedItem::groupKey).containsOnlyNulls();
        assertThat(grouped.get(0).questionStem()).isEqualTo("The museum was built ______ (2019) and the ticket was ______ (5).");
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
