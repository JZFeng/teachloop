package com.teachloop.vocab.quiz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 小测出题器（US-87 / T547，spec AC-87.2 / AC-87.3）。
 *
 * <p>纯函数、不碰数据库、不调模型：输入是「本次要考的词 + 它们在 v1 视图里的内容」，
 * 输出是一张卷面的题项。同一个 {@code seed} 必然出同一套题，便于复现与排查。
 *
 * <p>四条硬规则（都来自 spec，不是实现偏好）：
 * <ol>
 *   <li>只用例句里**真实出现**的词形挖空——句子必须读得通，所以先定位再替换，
 *       定位失败就不要这个例句（不做 AI 兜底）；</li>
 *   <li>同一句不得出现两次（同词两题也不能共用一句）；</li>
 *   <li>同一个词最多两题，且两题之间至少隔 {@value #MIN_GAP} 题；</li>
 *   <li>凑不出 4 个选项（干扰词不足）时该题降级为填空，**不出二选一**。</li>
 * </ol>
 *
 * <p>为什么干扰项用**原形**而不是句中的屈折形：如果正确答案是 {@code struggled}、
 * 干扰项是 {@code induce/abandon/dwell}，只看词形就能排除，题就废了。
 */
public final class QuizBuilder {

    public enum QuestionType { SINGLE_CHOICE, FILL_BLANK }

    /** 一个可出题的词：{@code view} 是 {@code ContentView} 产出的 v1 形状（senses/examples/family）。 */
    public record SourceWord(UUID id, String form, JsonNode view) {
    }

    public record Option(String key, String text) {
    }

    public record Item(UUID wordId, QuestionType type, String stem, String answerKey, List<Option> options) {
    }

    /** 题干上限：太长的句子在卷面上会折行，挖空后更难读。 */
    static final int MAX_SENTENCE = 140;

    /** 同一词两题的最小间隔（题号差）。 */
    static final int MIN_GAP = 5;

    /** 每题最多用该词几条例句。 */
    static final int MAX_PER_WORD = 2;

    private static final String BLANK = "____";

    private QuizBuilder() {
    }

    /**
     * 出一张卷。
     *
     * @param words 候选词（顺序无关，内部按 seed 打散）
     * @param size  期望题量；实际题量可能更少（可用例句不够），调用方负责「太少就不出卷」
     * @param seed  抽样种子
     */
    public static List<Item> build(List<SourceWord> words, int size, long seed) {
        if (words == null || words.isEmpty() || size <= 0) {
            return List.of();
        }
        Random random = new Random(seed);

        // 1) 每个词最多准备 MAX_PER_WORD 个「可挖空」例句
        Map<UUID, List<Candidate>> byWord = new LinkedHashMap<>();
        for (SourceWord word : words) {
            List<Candidate> found = candidates(word);
            if (!found.isEmpty()) {
                byWord.put(word.id(), found);
            }
        }
        if (byWord.isEmpty()) {
            return List.of();
        }

        // 1.5) 词序打散（同一个 seed 结果固定）
        List<UUID> order = new ArrayList<>(byWord.keySet());
        Collections.shuffle(order, random);

        // 2) 每个词出几题：默认 1 题；题量不够时，给「有第二条例句」的词加一题（最多 2 题）
        Map<UUID, Integer> quota = new LinkedHashMap<>();
        for (UUID id : order) {
            quota.put(id, 1);
        }
        int shortage = size - order.size();
        if (shortage > 0) {
            List<UUID> repeatable = new ArrayList<>();
            for (UUID id : order) {
                if (byWord.get(id).size() >= MAX_PER_WORD) {
                    repeatable.add(id);
                }
            }
            Collections.shuffle(repeatable, random);
            for (int i = 0; i < repeatable.size() && shortage > 0; i++, shortage--) {
                quota.put(repeatable.get(i), MAX_PER_WORD);
            }
        }

        // 3) 排题序：贪心「冷却 MIN_GAP 题」——每次挑最久没出现、且已经冷却的词。
        //    冷却不满足时宁可停（少出一题），也不把同一个词的题挨在一起。
        List<Candidate> picked = new ArrayList<>();
        Map<UUID, Integer> placed = new LinkedHashMap<>();
        Map<UUID, Integer> lastIndex = new LinkedHashMap<>();
        Set<String> usedSentences = new HashSet<>();
        while (picked.size() < size) {
            UUID best = null;
            int bestAge = Integer.MIN_VALUE;
            for (UUID id : order) {
                int done = placed.getOrDefault(id, 0);
                if (done >= quota.get(id)) {
                    continue;
                }
                Integer last = lastIndex.get(id);
                if (last != null && picked.size() - last < MIN_GAP) {
                    continue;
                }
                int age = last == null ? Integer.MAX_VALUE : picked.size() - last;
                if (age > bestAge) {
                    bestAge = age;
                    best = id;
                }
            }
            if (best == null) {
                break;
            }
            int done = placed.getOrDefault(best, 0);
            Candidate c = byWord.get(best).get(done);
            if (!usedSentences.add(c.sentence().toLowerCase())) {
                placed.put(best, quota.get(best));
                continue;
            }
            placed.put(best, done + 1);
            picked.add(c);
            lastIndex.put(best, picked.size() - 1);
        }

        // 4) 干扰词池 = 全部候选词的原形（决定能不能出选择题）
        List<String> pool = words.stream()
                .map(w -> w.form() == null ? "" : w.form().trim())
                .filter(f -> !f.isEmpty())
                .map(f -> f.toLowerCase())
                .distinct()
                .toList();
        Map<String, String> posByForm = new LinkedHashMap<>();
        for (SourceWord w : words) {
            posByForm.putIfAbsent(w.form() == null ? "" : w.form().trim().toLowerCase(), posOf(w.view()));
        }

        // 5) 逐题定题型：偶数位优先选择题（池子够大时），奇数位填空，保证一份卷里两种都有
        List<Item> items = new ArrayList<>(picked.size());
        for (int i = 0; i < picked.size(); i++) {
            Candidate c = picked.get(i);
            boolean choice = i % 2 == 0 && pool.size() >= 4;
            items.add(choice ? choiceItem(c, pool, posByForm, random) : fillItem(c));
        }
        return List.copyOf(items);
    }

    // ---------------------------------------------------------------- 内部

    private record Candidate(UUID wordId, String wordForm, String pos, String sentence,
                             String surface, int start, int end, String zh) {
    }

    private static List<Candidate> candidates(SourceWord word) {
        JsonNode examples = word.view() == null ? null : word.view().path("examples");
        if (examples == null || !examples.isArray()) {
            return List.of();
        }
        String form = word.form() == null ? "" : word.form().trim();
        if (form.isEmpty()) {
            return List.of();
        }
        List<String> forms = candidateForms(form, word.view());
        String pos = posOf(word.view());
        List<Candidate> out = new ArrayList<>(MAX_PER_WORD);
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode example : examples) {
            String sentence = example.path("en").asString("");
            if (sentence.isBlank() || sentence.length() > MAX_SENTENCE) {
                continue;
            }
            String key = sentence.toLowerCase();
            if (!seen.add(key)) {
                continue;
            }
            Surface hit = locate(sentence, forms);
            if (hit == null) {
                continue;
            }
            out.add(new Candidate(word.id(), form, pos, sentence, hit.text(), hit.start(), hit.end(),
                    example.path("zh").asString("")));
            if (out.size() >= MAX_PER_WORD) {
                break;
            }
        }
        return out;
    }

    private record Surface(String text, int start, int end) {
    }

    /**
     * 在句子里定位目标词形。
     *
     * <p>先试原形与词族词形；仍不中时补常用屈折（{@code -s/-es/-ed/-d/-ing}）。
     * 这一步是实测补出来的：{@code reside / inquire / precede} 的三个例句
     * （resides / inquired / preceded）都不在 {@code wordFamily} 里，
     * 不加回退会白白丢掉 3 个词（3000 词包里就这 3 个）。
     */
    static Surface locate(String sentence, List<String> forms) {
        String lower = sentence.toLowerCase(java.util.Locale.ROOT);
        for (String form : forms) {
            Matcher m = boundary(form).matcher(lower);
            if (m.find()) {
                return new Surface(sentence.substring(m.start(), m.end()), m.start(), m.end());
            }
        }
        return null;
    }

    private static Pattern boundary(String form) {
        return Pattern.compile("(?<![a-z])" + Pattern.quote(form) + "(?![a-z])");
    }

    /** 原形 + 词族 + 常用屈折回退，按「越接近原形越优先」的顺序。 */
    static List<String> candidateForms(String form, JsonNode view) {
        String base = form.toLowerCase(java.util.Locale.ROOT);
        LinkedHashSet<String> forms = new LinkedHashSet<>();
        forms.add(base);
        JsonNode family = view == null ? null : view.path("family");
        if (family != null && family.isArray()) {
            for (JsonNode member : family) {
                String word = member.asString("").trim().toLowerCase(java.util.Locale.ROOT);
                if (!word.isEmpty()) {
                    forms.add(word);
                }
            }
        }
        forms.add(base + "s");
        forms.add(base + "es");
        forms.add(base + "ed");
        forms.add(base + "d");
        forms.add(base + "ing");
        if (base.endsWith("e")) {
            String stem = base.substring(0, base.length() - 1);
            forms.add(stem + "ing");
            forms.add(stem + "ed");
        }
        if (base.endsWith("y")) {
            String stem = base.substring(0, base.length() - 1);
            forms.add(stem + "ies");
            forms.add(stem + "ied");
        }
        return List.copyOf(forms);
    }

    private static String posOf(JsonNode view) {
        JsonNode senses = view == null ? null : view.path("senses");
        if (senses == null || !senses.isArray() || senses.isEmpty()) {
            return "";
        }
        return senses.get(0).path("pos").asString("");
    }

    private static Item fillItem(Candidate c) {
        String stem = c.sentence().substring(0, c.start()) + BLANK + c.sentence().substring(c.end());
        return new Item(c.wordId(), QuestionType.FILL_BLANK, stem, c.surface(), List.of());
    }

    private static Item choiceItem(Candidate c, List<String> pool, Map<String, String> posByForm, Random random) {
        String answer = c.wordForm().toLowerCase();
        String pos = c.pos();
        List<String> samePos = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String candidate : pool) {
            if (candidate.equals(answer)) {
                continue;
            }
            String candidatePos = posByForm.getOrDefault(candidate, "");
            if (!pos.isEmpty() && !candidatePos.isEmpty() && candidatePos.equals(pos)) {
                samePos.add(candidate);
            } else {
                others.add(candidate);
            }
        }
        Collections.shuffle(samePos, random);
        Collections.shuffle(others, random);
        List<String> distractors = new ArrayList<>(3);
        for (String candidate : samePos) {
            if (distractors.size() >= 3) break;
            distractors.add(candidate);
        }
        for (String candidate : others) {
            if (distractors.size() >= 3) break;
            distractors.add(candidate);
        }
        if (distractors.size() < 3) {
            return fillItem(c);
        }
        List<String> texts = new ArrayList<>(4);
        texts.add(c.wordForm().trim());
        texts.addAll(distractors.subList(0, 3));
        Collections.shuffle(texts, random);

        String stem = c.sentence().substring(0, c.start()) + BLANK + c.sentence().substring(c.end());
        List<Option> options = new ArrayList<>(4);
        String key = null;
        for (int i = 0; i < texts.size(); i++) {
            String letter = String.valueOf((char) ('A' + i));
            options.add(new Option(letter, texts.get(i)));
            if (texts.get(i).equalsIgnoreCase(c.wordForm().trim())) {
                key = letter;
            }
        }
        if (key == null) {
            // 理论不可达：正确答案一定在 texts 里。落成填空比抛异常好——小测不该让整页 500。
            return fillItem(c);
        }
        return new Item(c.wordId(), QuestionType.SINGLE_CHOICE, stem, key, List.copyOf(options));
    }
}
