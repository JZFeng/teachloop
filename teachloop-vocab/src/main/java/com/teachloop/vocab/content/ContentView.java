package com.teachloop.vocab.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 内容结构适配（T431 / decisions/018）：全局内容包与 delta 存的是 v2 结构
 * （{@code schemaVersion=2}：basicInfo / meanings / examples / wordFormation / memoryAids /
 * wordFamily），前端当前仍按 v1 形状渲染（senses / examples[{en,zh}] / analysis / family /
 * phonetic）。
 *
 * <p>读路径（队列、会话当前卡、详情、词库列表）返回内容前统一过这里：
 * <ul>
 *   <li>v2 → 映射成 v1：{@code senses=[{pos, zh, en}]}、{@code examples=[{en, zh}]} 按
 *       {@code popularity} 升序（缺 popularity 的排在末尾、彼此保持原有相对顺序）、
 *       {@code analysis=wordFormation.analysis 或 memoryAids.supplement}、
 *       {@code family=wordFamily.items[].word（去重）}、{@code phonetic=basicInfo.pronunciation}；</li>
 *   <li>v1（没有 schemaVersion 或不是 2）→ 原样返回，不动存量 AI 内容的形状。</li>
 * </ul>
 *
 * <p>这一层是过渡：T432 前端切到 v2 后可以删掉，届时读路径直接返回原始 JSON。
 */
public final class ContentView {

    private static final Logger log = LoggerFactory.getLogger(ContentView.class);

    private static final int SCHEMA_V2 = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContentView() {
    }

    /**
     * 把一个内容 JSON 转成前端当前使用的形状。
     *
     * @param raw 原始内容；null 原样返回（词条还没准备内容）
     * @return v2 的 v1 视图；v1 或无法识别的内容原样返回
     */
    public static JsonNode toView(JsonNode raw) {
        if (raw == null || raw.isNull() || !raw.isObject()) {
            return raw;
        }
        if (!isSchemaV2(raw)) {
            return raw;
        }
        ObjectNode view = MAPPER.createObjectNode();
        putSenses(view, raw);
        putExamples(view, raw);
        putAnalysis(view, raw);
        putFamily(view, raw);
        putPhonetic(view, raw);
        return view;
    }

    /**
     * 读路径入口：按 {@code vocab_words.content_id} 从全局内容源取内容并适配。
     *
     * <p>取不到（content_id 为空、内容包缺这条、delta 读失败）时返回 {@code null}，
     * 页面显示「未准备」/空，不抛异常、不影响复习。
     */
    public static JsonNode resolve(ContentStore store, Long contentId) {
        if (store == null || contentId == null || contentId <= 0) {
            return null;
        }
        return store.getById(contentId)
                .map(StoredContent::json)
                .map(ContentView::parse)
                .map(ContentView::toView)
                .orElse(null);
    }

    private static boolean isSchemaV2(JsonNode raw) {
        JsonNode version = raw.get("schemaVersion");
        return version != null && version.isNumber() && version.asInt() == SCHEMA_V2;
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (RuntimeException ex) {
            log.warn("内容 JSON 解析失败（按未准备处理）：{}", ex.toString());
            return null;
        }
    }

    /** v2 的词性 + zh/en 释义合成一条 sense（v1 只有一个 sense 的位置）。 */
    private static void putSenses(ObjectNode view, JsonNode raw) {
        List<String> partsOfSpeech = new ArrayList<>();
        JsonNode parts = raw.path("basicInfo").path("partOfSpeech");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.isString() && !part.asString().isBlank()) {
                    partsOfSpeech.add(part.asString().strip());
                }
            }
        }
        String zh = text(raw.path("meanings"), "zh");
        String en = text(raw.path("meanings"), "en");
        if (partsOfSpeech.isEmpty() && zh == null && en == null) {
            return;
        }
        ObjectNode sense = view.putArray("senses").addObject();
        if (!partsOfSpeech.isEmpty()) {
            sense.put("pos", String.join(", ", partsOfSpeech));
        }
        if (zh != null) {
            sense.put("zh", zh);
        }
        if (en != null) {
            sense.put("en", en);
        }
    }

    /** 例句按 popularity 升序；没有 popularity 的排在末尾并保持原有相对顺序。 */
    private static void putExamples(ObjectNode view, JsonNode raw) {
        JsonNode examples = raw.path("examples");
        if (!examples.isArray() || examples.isEmpty()) {
            return;
        }
        List<Example> ordered = new ArrayList<>();
        int index = 0;
        for (JsonNode node : examples) {
            JsonNode popularity = node.get("popularity");
            boolean hasPopularity = popularity != null && popularity.isNumber();
            ordered.add(new Example(node, index++, hasPopularity,
                    hasPopularity ? popularity.asInt() : Integer.MAX_VALUE));
        }
        ordered.sort(Comparator
                .comparing(Example::hasPopularity).reversed()
                .thenComparingInt(Example::popularity)
                .thenComparingInt(Example::index));
        ArrayNode target = view.putArray("examples");
        for (Example example : ordered) {
            String en = text(example.node(), "text");
            String zh = text(example.node().path("translations"), "zh");
            if (en == null && zh == null) {
                continue;
            }
            ObjectNode item = target.addObject();
            if (en != null) {
                item.put("en", en);
            }
            if (zh != null) {
                item.put("zh", zh);
            }
        }
        if (target.isEmpty()) {
            view.remove("examples");
        }
    }

    /** 构词/来源说明优先，其次记忆辅助的补充说明。 */
    private static void putAnalysis(ObjectNode view, JsonNode raw) {
        String analysis = text(raw.path("wordFormation"), "analysis");
        if (analysis == null) {
            analysis = text(raw.path("memoryAids"), "supplement");
        }
        if (analysis != null) {
            view.put("analysis", analysis);
        }
    }

    /** 词族：v2 是 items[{word, ...}]，v1 只要词形列表；重复词形只留一次。 */
    private static void putFamily(ObjectNode view, JsonNode raw) {
        Set<String> words = new LinkedHashSet<>();
        JsonNode items = raw.path("wordFamily").path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                String word = text(item, "word");
                if (word != null) {
                    words.add(word);
                }
            }
        }
        if (words.isEmpty()) {
            return;
        }
        ArrayNode family = view.putArray("family");
        words.forEach(family::add);
    }

    private static void putPhonetic(ObjectNode view, JsonNode raw) {
        JsonNode pronunciation = raw.path("basicInfo").path("pronunciation");
        String uk = text(pronunciation, "uk");
        String us = text(pronunciation, "us");
        if (uk == null && us == null) {
            return;
        }
        ObjectNode phonetic = view.putObject("phonetic");
        if (uk != null) {
            phonetic.put("uk", uk);
        }
        if (us != null) {
            phonetic.put("us", us);
        }
    }

    /** 取一个非空字符串字段；缺失/非字符串/空白都当没有。 */
    private static String text(JsonNode parent, String field) {
        if (parent == null || parent.isMissingNode()) {
            return null;
        }
        JsonNode node = parent.get(field);
        if (node == null || !node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }

    private record Example(JsonNode node, int index, boolean hasPopularity, int popularity) {
    }
}
