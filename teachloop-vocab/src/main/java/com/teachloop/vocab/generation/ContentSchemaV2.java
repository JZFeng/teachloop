package com.teachloop.vocab.generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * v2 词内容的结构校验与清洗（AC-60.9 / AC-60.12，与 Python 批量工具的 {@code validate()} 同规则）。
 *
 * <p>校验之外的噪音就地清洗，避免为了一个坏例句整词重试（3,000 词批量实测把失败率从 7% 压到 0.2%）：
 * 不含目标词的例句、重复句子直接丢弃并重排 {@code popularity}；同族词去重（含词头自身）；
 * {@code wordFormation.parts} 字段不全的切分项丢弃。清洗后仍不合格才返回问题，由调用方重试。
 */
public final class ContentSchemaV2 {

    private static final Set<String> CONFIDENCE = Set.of("high", "medium", "low");
    private static final List<String> SUFFIXES = List.of(
            "s", "es", "ed", "d", "ing", "ly", "er", "est", "ies", "ied", "ier", "iest");

    /** 词表（basewords 成员表）不全，常用不规则屈折在这里兜底；与 Python 侧同一份思路。 */
    private static final Map<String, List<String>> IRREGULAR = Map.ofEntries(
            Map.entry("be", List.of("am", "is", "are", "was", "were", "been", "being")),
            Map.entry("have", List.of("has", "had", "having")),
            Map.entry("do", List.of("does", "did", "done", "doing")),
            Map.entry("go", List.of("goes", "went", "gone", "going")),
            Map.entry("get", List.of("gets", "got", "gotten", "getting")),
            Map.entry("make", List.of("makes", "made", "making")),
            Map.entry("take", List.of("takes", "took", "taken", "taking")),
            Map.entry("see", List.of("sees", "saw", "seen", "seeing")),
            Map.entry("come", List.of("comes", "came", "coming")),
            Map.entry("give", List.of("gives", "gave", "given", "giving")),
            Map.entry("leave", List.of("leaves", "left", "leaving")),
            Map.entry("feel", List.of("feels", "felt", "feeling")),
            Map.entry("find", List.of("finds", "found", "finding")),
            Map.entry("think", List.of("thinks", "thought", "thinking")),
            Map.entry("write", List.of("writes", "wrote", "written", "writing")),
            Map.entry("speak", List.of("speaks", "spoke", "spoken", "speaking")),
            Map.entry("break", List.of("breaks", "broke", "broken", "breaking")),
            Map.entry("choose", List.of("chooses", "chose", "chosen", "choosing")),
            Map.entry("bite", List.of("bites", "bit", "bitten", "biting")),
            Map.entry("wind", List.of("winds", "wound", "winding")),
            Map.entry("eat", List.of("eats", "ate", "eaten", "eating")),
            Map.entry("drink", List.of("drinks", "drank", "drunk", "drinking")),
            Map.entry("fly", List.of("flies", "flew", "flown", "flying")),
            Map.entry("grow", List.of("grows", "grew", "grown", "growing")),
            Map.entry("lose", List.of("loses", "lost", "losing")),
            Map.entry("fall", List.of("falls", "fell", "fallen", "falling")),
            Map.entry("sell", List.of("sells", "sold", "selling")),
            Map.entry("teach", List.of("teaches", "taught", "teaching")),
            Map.entry("child", List.of("children")),
            Map.entry("man", List.of("men")),
            Map.entry("woman", List.of("women")),
            Map.entry("person", List.of("people")),
            Map.entry("leaf", List.of("leaves")),
            Map.entry("life", List.of("lives")),
            Map.entry("good", List.of("better", "best")),
            Map.entry("bad", List.of("worse", "worst")));

    private ContentSchemaV2() {
    }

    /**
     * 校验并就地清洗。
     *
     * @return 问题列表；为空表示内容可用（清洗后的结果已写回 {@code content}）
     */
    public static List<String> validateAndClean(ObjectNode content, String form, String language,
                                                int minExamples, int maxExamples) {
        List<String> problems = new ArrayList<>();
        if (asInt(content, "schemaVersion", -1) != 2) {
            problems.add("schemaVersion != 2");
        }
        if (!text(content, "word").equalsIgnoreCase(form.strip())) {
            problems.add("word mismatch: " + text(content, "word"));
        }
        if (!text(content, "language").equals(language)) {
            problems.add("language mismatch: " + text(content, "language"));
        }

        JsonNode basic = content.path("basicInfo");
        JsonNode pronunciation = basic.path("pronunciation");
        if (text(pronunciation, "uk").isBlank() || text(pronunciation, "us").isBlank()) {
            problems.add("basicInfo.pronunciation missing uk/us");
        }
        if (!basic.path("partOfSpeech").isArray() || basic.path("partOfSpeech").isEmpty()) {
            problems.add("basicInfo.partOfSpeech empty");
        }

        JsonNode meanings = content.path("meanings");
        if (text(meanings, "zh").isBlank()) {
            problems.add("meanings.zh empty");
        }
        if (text(meanings, "en").isBlank()) {
            problems.add("meanings.en empty");
        }

        problems.addAll(cleanExamples(content, form, language, minExamples, maxExamples));
        problems.addAll(cleanFormation(content));
        problems.addAll(cleanFamily(content, form));
        return problems;
    }

    private static List<String> cleanExamples(ObjectNode content, String form, String language,
                                              int minExamples, int maxExamples) {
        List<String> problems = new ArrayList<>();
        JsonNode examples = content.path("examples");
        if (!examples.isArray() || examples.isEmpty()) {
            problems.add("examples must be 1.." + maxExamples);
            return problems;
        }
        Set<String> forms = formsOf(form);
        Set<String> seen = new LinkedHashSet<>();
        List<ObjectNode> kept = new ArrayList<>();
        for (JsonNode example : examples) {
            if (!example.isObject()) {
                continue;
            }
            ObjectNode node = (ObjectNode) example;
            String original = text(node, "text").strip();
            if (original.isEmpty()) {
                continue;
            }
            String key = original.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                continue;
            }
            boolean contains = containsForm(original, forms)
                    || containsForm(text(node, "usagePattern"), forms);
            if (!contains) {
                seen.remove(key);
                continue;
            }
            kept.add(node);
        }
        if (kept.size() > maxExamples) {
            kept = new ArrayList<>(kept.subList(0, maxExamples));
        }
        if (kept.size() < minExamples) {
            problems.add("examples: no sentence contains the target word");
            return problems;
        }
        ArrayNode cleaned = content.putArray("examples");
        for (int index = 0; index < kept.size(); index++) {
            ObjectNode node = kept.get(index);
            node.put("popularity", index + 1);
            if (text(node, "partOfSpeech").isBlank()) {
                problems.add("examples[" + index + "].partOfSpeech empty");
            }
            if (text(node, "usagePattern").isBlank()) {
                problems.add("examples[" + index + "].usagePattern empty");
            }
            JsonNode translations = node.path("translations");
            for (String candidate : ContentSchemaV2.LANGUAGES_EXPECTED) {
                if (candidate.equals(language)) {
                    continue;
                }
                if (text(translations, candidate).isBlank()) {
                    problems.add("examples[" + index + "].translations." + candidate + " empty");
                }
            }
            cleaned.add(node);
        }
        return problems;
    }

    /** 目前只产 zh/en；扩展语言时与契约的 languages 保持一致。 */
    private static final List<String> LANGUAGES_EXPECTED = List.of("zh", "en");

    private static List<String> cleanFormation(ObjectNode content) {
        List<String> problems = new ArrayList<>();
        JsonNode formation = content.get("wordFormation");
        if (formation == null || formation.isNull()) {
            return problems;
        }
        if (!formation.isObject()) {
            problems.add("wordFormation must be an object");
            return problems;
        }
        ObjectNode node = (ObjectNode) formation;
        if (text(node, "analysis").isBlank()) {
            problems.add("wordFormation.analysis empty");
        }
        String confidence = text(node, "confidence");
        if (!confidence.isBlank() && !CONFIDENCE.contains(confidence)) {
            problems.add("wordFormation.confidence invalid");
        }
        JsonNode parts = node.path("parts");
        if (parts.isArray()) {
            ArrayNode cleaned = node.putArray("parts");
            for (JsonNode part : parts) {
                if (!part.isObject()) {
                    continue;
                }
                if (text(part, "text").isBlank() || text(part, "type").isBlank()
                        || text(part, "meaning").isBlank()) {
                    continue;
                }
                cleaned.add(part);
            }
        }
        return problems;
    }

    private static List<String> cleanFamily(ObjectNode content, String form) {
        List<String> problems = new ArrayList<>();
        JsonNode family = content.path("wordFamily").path("items");
        if (!family.isArray()) {
            problems.add("wordFamily.items missing");
            return problems;
        }
        Set<String> seen = new LinkedHashSet<>();
        seen.add(form.toLowerCase(Locale.ROOT));
        List<ObjectNode> kept = new ArrayList<>();
        for (JsonNode item : family) {
            if (!item.isObject()) {
                problems.add("wordFamily item is not an object");
                continue;
            }
            ObjectNode node = (ObjectNode) item;
            String word = text(node, "word").strip().toLowerCase(Locale.ROOT);
            if (word.isEmpty()) {
                problems.add("wordFamily item word empty");
                continue;
            }
            if (!seen.add(word)) {
                continue;
            }
            if (kept.size() < 6) {
                kept.add(node);
            }
        }
        ObjectNode wrapper = content.putObject("wordFamily");
        ArrayNode cleaned = wrapper.putArray("items");
        for (int index = 0; index < kept.size(); index++) {
            ObjectNode node = kept.get(index);
            if (!node.path("partOfSpeech").isArray() || node.path("partOfSpeech").isEmpty()) {
                problems.add("wordFamily[" + index + "].partOfSpeech empty");
            }
            JsonNode meanings = node.path("meanings");
            for (String candidate : LANGUAGES_EXPECTED) {
                if (text(meanings, candidate).isBlank()) {
                    problems.add("wordFamily[" + index + "].meanings." + candidate + " empty");
                }
            }
            cleaned.add(node);
        }
        return problems;
    }

    /** 目标词或它的标准屈折形式出现在句子里（非字母边界，避免 is 命中 this）。 */
    static boolean containsForm(String text, Set<String> forms) {
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String form : forms) {
            if (form.isBlank()) {
                continue;
            }
            Pattern pattern = Pattern.compile("(?<![a-z])" + Pattern.quote(form) + "(?![a-z])");
            if (pattern.matcher(lowered).find()) {
                return true;
            }
        }
        return false;
    }

    static Set<String> formsOf(String word) {
        String base = word.strip().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> forms = new LinkedHashSet<>();
        if (base.isEmpty()) {
            return forms;
        }
        forms.add(base);
        for (String suffix : SUFFIXES) {
            forms.add(base + suffix);
        }
        if (base.endsWith("e")) {
            forms.add(base + "d");
            forms.add(base.substring(0, base.length() - 1) + "ing");
        }
        if (base.endsWith("f")) {
            forms.add(base.substring(0, base.length() - 1) + "ves");
        }
        if (base.endsWith("fe")) {
            forms.add(base.substring(0, base.length() - 2) + "ves");
        }
        if (base.endsWith("y") && base.length() > 2) {
            String stem = base.substring(0, base.length() - 1);
            forms.add(stem + "ies");
            forms.add(stem + "ied");
            forms.add(stem + "ier");
            forms.add(stem + "iest");
        }
        if (base.length() > 2 && "aeiou".indexOf(base.charAt(base.length() - 1)) < 0
                && "aeiou".indexOf(base.charAt(base.length() - 2)) >= 0) {
            char last = base.charAt(base.length() - 1);
            forms.add(base + last + "ed");
            forms.add(base + last + "ing");
        }
        forms.addAll(IRREGULAR.getOrDefault(base, List.of()));
        return forms;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.asString() : "";
    }

    private static int asInt(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.asInt() : fallback;
    }

    /** 仅测试用：把问题列表压成一行，便于断言。 */
    static String join(List<String> problems) {
        return String.join("; ", problems);
    }

    @SuppressWarnings("unused")
    private static Map<String, String> emptyMap() {
        return new LinkedHashMap<>();
    }
}
