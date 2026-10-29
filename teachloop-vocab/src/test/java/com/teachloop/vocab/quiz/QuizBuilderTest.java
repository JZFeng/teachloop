package com.teachloop.vocab.quiz;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.vocab.quiz.QuizBuilder.Item;
import com.teachloop.vocab.quiz.QuizBuilder.QuestionType;
import com.teachloop.vocab.quiz.QuizBuilder.SourceWord;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** T547：小测出题器的四条硬规则（AC-87.2 / AC-87.3）。 */
class QuizBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode view(String pos, String... examples) {
        StringBuilder json = new StringBuilder("{\"senses\":[{\"pos\":\"" + pos + "\"}],\"examples\":[");
        for (int i = 0; i < examples.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"en\":\"").append(esc(examples[i])).append("\",\"zh\":\"译").append(i).append("\"}");
        }
        return MAPPER.readTree(json.append("]}").toString());
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static SourceWord word(String form, String pos, String... examples) {
        return new SourceWord(UUID.randomUUID(), form, view(pos, examples));
    }

    @Test
    @DisplayName("-e 动词的例句用屈折形（resides / inquired / preceded）也能出题")
    void locatesInflectedEVerb() {
        List<Item> items = QuizBuilder.build(
                List.of(word("reside", "verb", "She resides in London.")), 1, 1L);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).stem()).isEqualTo("She ____ in London.");
        // 填空答案保留句中的真实词形，判分时按词族放宽
        assertThat(items.get(0).answerKey()).isEqualTo("resides");
        assertThat(items.get(0).type()).isEqualTo(QuestionType.FILL_BLANK);
    }

    @Test
    @DisplayName("干扰词不足 4 个时降级为填空，不出二选一")
    void downgradesToFillWhenPoolTooSmall() {
        List<Item> items = QuizBuilder.build(List.of(
                word("induce", "verb", "Nothing could induce me to change my mind."),
                word("dwell", "verb", "Don't dwell on your mistakes.")), 2, 7L);

        assertThat(items).hasSize(2);
        assertThat(items).allMatch(item -> item.type() == QuestionType.FILL_BLANK);
        assertThat(items).allMatch(item -> item.options().isEmpty());
    }

    @Test
    @DisplayName("词够多时出选择题，且选项里只有一个正确答案")
    void buildsSingleChoiceFromPool() {
        List<Item> items = QuizBuilder.build(List.of(
                word("induce", "verb", "Nothing could induce me to change my mind."),
                word("abandon", "verb", "They had to abandon the car."),
                word("dwell", "verb", "Don't dwell on your mistakes."),
                word("glance", "verb", "She glanced at her watch."),
                word("struggle", "verb", "She struggled to open the jar.")), 5, 3L);

        assertThat(items).isNotEmpty();
        assertThat(items.get(0).type()).isEqualTo(QuestionType.SINGLE_CHOICE);
        assertThat(items.get(0).options()).hasSize(4);
        assertThat(items.get(0).options())
                .extracting(QuizBuilder.Option::key)
                .containsExactly("A", "B", "C", "D");
        assertThat(items.get(0).stem()).contains("____");
    }

    @Test
    @DisplayName("同一句不会出现两次（同词两题也不能共用一句）")
    void neverReusesSentence() {
        List<Item> items = QuizBuilder.build(List.of(
                word("struggle", "verb", "She struggled to open the jar.", "He struggled with the box."),
                word("induce", "verb", "Nothing could induce me to change my mind."),
                word("abandon", "verb", "They had to abandon the car."),
                word("dwell", "verb", "Don't dwell on your mistakes."),
                word("glance", "verb", "She glanced at her watch."),
                word("precede", "verb", "The ceremony was preceded by a speech.")), 8, 11L);

        List<String> stems = items.stream().map(Item::stem).toList();
        assertThat(stems).doesNotHaveDuplicates();
        assertThat(items.size()).isGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("同一个词的两题至少隔 5 题")
    void keepsSameWordApart() {
        List<SourceWord> words = new ArrayList<>();
        words.add(word("struggle", "verb", "She struggled to open the jar.", "He struggled with the box."));
        words.add(word("induce", "verb", "Nothing could induce me to change my mind."));
        words.add(word("abandon", "verb", "They had to abandon the car."));
        words.add(word("dwell", "verb", "Don't dwell on your mistakes."));
        words.add(word("glance", "verb", "She glanced at her watch."));
        words.add(word("precede", "verb", "The ceremony was preceded by a speech."));

        List<Item> items = QuizBuilder.build(words, 7, 5L);
        // 只有 struggle 有第二条例句，能不能重复取决于它落在第几题（必须留出 5 题冷却），
        // 所以这里只断言「出了 6 题以上」与下面的间隔规则，不断言一定 7 题。
        assertThat(items.size()).isBetween(6, 7);

        // 词序被打散，重复的是哪一个词不定；断言的是“任何出现两次的词都必须隔 ≥5 题”
        java.util.Map<UUID, List<Integer>> seen = new java.util.HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            seen.computeIfAbsent(items.get(i).wordId(), k -> new ArrayList<>()).add(i);
        }
        long repeated = seen.values().stream().filter(list -> list.size() == 2).count();
        assertThat(repeated).isLessThanOrEqualTo(1);
        seen.values().forEach(list -> {
            if (list.size() == 2) {
                assertThat(list.get(1) - list.get(0)).isGreaterThanOrEqualTo(QuizBuilder.MIN_GAP);
            }
        });
    }

    @Test
    @DisplayName("同一个 seed 出同一套题；句子过期（>140 字符）不用")
    void deterministicAndSkipsLongSentence() {
        String longSentence = "She " + "very ".repeat(30) + "struggled to open the jar.";
        List<SourceWord> words = List.of(
                word("struggle", "verb", "She struggled to open the jar."),
                word("induce", "verb", longSentence),
                word("abandon", "verb", "They had to abandon the car."),
                word("dwell", "verb", "Don't dwell on your mistakes."));

        List<Item> first = QuizBuilder.build(words, 4, 42L);
        List<Item> second = QuizBuilder.build(words, 4, 42L);

        assertThat(first).hasSize(second.size());
        assertThat(first.stream().map(Item::stem).toList())
                .containsExactlyElementsOf(second.stream().map(Item::stem).toList());
        assertThat(first).noneMatch(item -> item.stem().contains("very very"));
    }
}
