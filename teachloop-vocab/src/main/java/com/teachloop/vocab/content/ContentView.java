package com.teachloop.vocab.content;

import com.teachloop.vocab.word.WordNormalizer;
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
        putCoreSemantics(view, raw);
        putDerivation(view, raw);
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
        return resolve(store, contentId, null, null);
    }

    /**
     * 读路径入口（带词形校验）：内容包的 contentId 是按 normalizedForm 排序后分配的 1..N，
     * 重新出包（增词）会让 id 指向另一条记录。这里先按 id 取，再核对记录词形；不一致或取不到时
     * 按 {@code (language, form)} 重新解析，避免把旧 id 解析成别的词的内容。
     *
     * <p>delta 的 contentId 是稳定的，正常路径只多一次字符串比较；只有包重建后才走回退查询。
     */
    public static JsonNode resolve(ContentStore store, Long contentId, String language, String form) {
        if (store == null) {
            return null;
        }
        String expected = form == null ? "" : WordNormalizer.normalized(form);
        if (contentId != null && contentId > 0) {
            JsonNode byId = store.getById(contentId)
                    .filter(content -> expected.isEmpty()
                            || expected.equals(content.normalizedForm()))
                    .map(StoredContent::json)
                    .map(ContentView::parse)
                    .map(ContentView::toView)
                    .orElse(null);
            if (byId != null) {
                return byId;
            }
        }
        if (expected.isEmpty()) {
            return null;
        }
        return store.get(language, form)
                .map(StoredContent::json)
                .map(ContentView::parse)
                .map(ContentView::toView)
                .orElse(null);
    }

    /**
     * 读路径的状态口径：库里的 {@code content_status} 可能已经过期（内容包换版、提示词升级、
     * delta 被清），此时仍写着 READY 但内容解析不到。前端只认这个状态，所以必须在这里降级成
     * PENDING（= 内容待准备），否则会渲染一张没有音标/释义/例句的空卡，而且永远不会重新生成
     * （2026-10-29 用户报 Bug）。
     *
     * @param storedStatus {@code vocab_words.content_status}
     * @param content      按 id/词形解析后的内容；null 表示解析不到
     */
    public static String statusFor(String storedStatus, JsonNode content) {
        if (content == null && "READY".equals(storedStatus)) {
            return "PENDING";
        }
        return storedStatus;
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

    /** v2.5 的语义核心 / 不是什么 / 一句话画面；没有就跳过。 */
    private static void putCoreSemantics(ObjectNode view, JsonNode raw) {
        JsonNode core = raw.path("coreSemantics");
        if (!core.isObject()) {
            return;
        }
        String text = text(core, "core");
        String notThis = text(core, "notThis");
        String image = text(core, "mentalImage");
        if (text == null && notThis == null && image == null) {
            return;
        }
        ObjectNode out = view.putObject("coreSemantics");
        if (text != null) {
            out.put("core", text);
        }
        if (notThis != null) {
            out.put("notThis", notThis);
        }
        if (image != null) {
            out.put("mentalImage", image);
        }
        String art = text(core, "image");
        if (art != null && isSafeSvg(art)) {
            out.put("image", art.strip());
        }
    }

    /** 只放行最朴素的 inline SVG：≤2KB、无脚本、无事件属性、无外链。 */
    static boolean isSafeSvg(String image) {
        String value = image.strip();
        if (value.length() > 2000 || !value.startsWith("<svg")) {
            return false;
        }
        String lowered = value.toLowerCase(java.util.Locale.ROOT);
        return !lowered.contains("<script") && !lowered.contains("foreignobject")
                && !lowered.contains("href") && !java.util.regex.Pattern
                .compile("\\son[a-z]+\\s*=").matcher(lowered).find();
    }

    /** v2 的 parts / chain 原样带出，供前端渲染「构词与派生」；v2.3 之前没有这两个字段就跳过。 */
    private static void putDerivation(ObjectNode view, JsonNode raw) {
        JsonNode formation = raw.path("wordFormation");
        JsonNode parts = formation.path("parts");
        JsonNode chain = formation.path("chain");
        boolean hasParts = parts.isArray() && !parts.isEmpty();
        boolean hasChain = chain.isArray() && !chain.isEmpty();
        if (!hasParts && !hasChain) {
            return;
        }
        ObjectNode derivation = view.putObject("derivation");
        if (hasParts) {
            ArrayNode out = derivation.putArray("parts");
            for (JsonNode part : parts) {
                ObjectNode item = out.addObject();
                String text = text(part, "text");
                String type = text(part, "type");
                String meaning = text(part, "meaning");
                String origin = text(part, "origin");
                if (text != null) {
                    item.put("text", text);
                }
                if (type != null) {
                    item.put("type", type);
                }
                if (meaning != null) {
                    item.put("meaning", meaning);
                }
                if (origin != null) {
                    item.put("origin", origin);
                }
            }
        }
        if (hasChain) {
            ArrayNode out = derivation.putArray("chain");
            for (JsonNode step : chain) {
                ObjectNode item = out.addObject();
                ArrayNode from = item.putArray("from");
                JsonNode sources = step.path("from");
                if (sources.isArray()) {
                    for (JsonNode source : sources) {
                        if (source.isString() && !source.asString().isBlank()) {
                            from.add(source.asString().strip());
                        }
                    }
                }
                String to = text(step, "to");
                String pos = text(step, "partOfSpeech");
                String meaning = text(step, "meaning");
                if (to != null) {
                    item.put("to", to);
                }
                if (pos != null) {
                    item.put("partOfSpeech", pos);
                }
                if (meaning != null) {
                    item.put("meaning", meaning);
                }
            }
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
