package com.teachloop.vocab.content;

import com.teachloop.vocab.review.ReviewPrefs;
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
 * <p>T594 起 v2 视图额外带两样东西（内容 JSON 的 schema 不变，只改读路径的输出形状）：
 * <ul>
 *   <li>{@code image={url}}：{@code basicInfo.image} 是安全 URL 时才输出；</li>
 *   <li>{@code blocks=[{id, payload}]}：按用户偏好的顺序排好的块列表，见 {@link #toView(JsonNode, List, List)}。</li>
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
     * 把一个内容 JSON 转成前端当前使用的形状（默认块顺序、不隐藏）。
     *
     * @param raw 原始内容；null 原样返回（词条还没准备内容）
     * @return v2 的 v1 视图（含 {@code blocks}）；v1 或无法识别的内容原样返回（不含 {@code blocks}）
     */
    public static JsonNode toView(JsonNode raw) {
        return toView(raw, ReviewPrefs.ALL_BLOCKS, List.of());
    }

    /**
     * 同 {@link #toView(JsonNode)}，但按调用方给的块顺序与隐藏集合排 {@code blocks}。
     *
     * <p><b>为什么由后端排顺序</b>：前端此前按 key 硬编码从 {@code content.*} 逐块取值、再自己按
     * {@code prefs.blocks} 排；同一份「顺序 / 隐藏 / 缺省」契约被前后端各实现一遍，改一处就漏一处
     * （T594 把图片加成第 9 块正是这种漏）。现在后端输出**有序、已过滤**的 {@code blocks}，前端只负责
     * 渲染，顺序与开关只有服务端这一个属主。
     *
     * <p>规则：
     * <ul>
     *   <li>顺序 = {@code blockOrder}（用户 {@code prefs.blocks}）；去重；不在
     *       {@link ReviewPrefs#ALL_BLOCKS} 里的 id 忽略；</li>
     *   <li>{@code hidden} 里的 id 不输出（顺序与开关是两个系统，不能靠「不在顺序里」表达关闭）；</li>
     *   <li>payload 缺失或为空（空数组 / 空对象）时**跳过该块**，不输出空壳；</li>
     *   <li>payload 是 view 里对应字段的**原样值**（同一个节点，不新造形状）；</li>
     *   <li>v2 内容即使一块都没有也输出 {@code blocks: []}——前端靠「数组在场」区分
     *       「后端按契约说没有」与「旧数据没有这个字段」；</li>
     *   <li>v1 / 未知版本原样返回、**不输出 blocks**：旧内容没有 v2 结构，前端据此回退到按 key 读旧字段
     *       （兼容一个版本，等 T432 前端切 v2 后与这一层一起删）。</li>
     * </ul>
     *
     * @param blockOrder 块顺序；null 用 {@link ReviewPrefs#ALL_BLOCKS}
     * @param hidden     不输出的块 id；null 视为不隐藏
     */
    public static JsonNode toView(JsonNode raw, List<String> blockOrder, List<String> hidden) {
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
        putMemoryAids(view, raw);
        putPhonetic(view, raw);
        // 图片必须排在 putBlocks 之前：image 块的 payload 就是这里放进 view 的 image 字段。
        putImage(view, raw);
        putBlocks(view, blockOrder, hidden);
        return view;
    }

    /**
     * 读路径入口：按 {@code vocab_words.content_id} 从全局内容源取内容并适配。
     *
     * <p>取不到（content_id 为空、内容包缺这条、delta 读失败）时返回 {@code null}，
     * 页面显示「未准备」/空，不抛异常、不影响复习。
     */
    public static JsonNode resolve(ContentStore store, Long contentId) {
        return resolve(store, contentId, null, null, null, null);
    }

    /**
     * 读路径入口（带词形校验）：内容包的 contentId 是按 normalizedForm 排序后分配的 1..N，
     * 重新出包（增词）会让 id 指向另一条记录。这里先按 id 取，再核对记录词形；不一致或取不到时
     * 按 {@code (language, form)} 重新解析，避免把旧 id 解析成别的词的内容。
     *
     * <p>delta 的 contentId 是稳定的，正常路径只多一次字符串比较；只有包重建后才走回退查询。
     */
    public static JsonNode resolve(ContentStore store, Long contentId, String language, String form) {
        return resolve(store, contentId, language, form, null, null);
    }

    /**
     * 同 {@link #resolve(ContentStore, Long, String, String)}，但按用户偏好的顺序/隐藏集合排
     * {@code blocks}（T594：今日复习队列与自由复习会话用它；详情、小测、词库列表继续用单参版本，
     * 走默认顺序且不隐藏）。
     *
     * <p>只有「怎么排块」不同，取数路径与回退语义一字未改：id 取不到 / 词形核对不上仍按
     * {@code (language, form)} 回退查询，都取不到才返回 null。
     *
     * @param blockOrder 块顺序；null 用 {@link ReviewPrefs#ALL_BLOCKS}
     * @param hidden     不输出的块 id；null 视为不隐藏
     */
    public static JsonNode resolve(ContentStore store, Long contentId, String language, String form,
                                   List<String> blockOrder, List<String> hidden) {
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
                    .map(raw -> toView(raw, blockOrder, hidden))
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
                .map(raw -> toView(raw, blockOrder, hidden))
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
    /**
     * US-88 / T554：近义词与反义词（v2 {@code memoryAids.synonyms/antonyms} → v1 同名字段）。
     *
     * <p>这两块之前在读路径里**完全没有映射**，所以卡面即使想显示也拿不到数据；
     * 每项保留 {@code word / pos / nuance}——{@code nuance} 是「与目标词的差别」，是这个块真正的价值。
     */
    private static void putMemoryAids(ObjectNode view, JsonNode raw) {
        JsonNode aids = raw.path("memoryAids");
        if (!aids.isObject()) {
            return;
        }
        putWordList(view, "synonyms", aids.path("synonyms"));
        putWordList(view, "antonyms", aids.path("antonyms"));
    }

    private static void putWordList(ObjectNode view, String field, JsonNode source) {
        if (!source.isArray() || source.isEmpty()) {
            return;
        }
        ArrayNode out = view.putArray(field);
        for (JsonNode item : source) {
            String word = item.path("word").asString("");
            if (word.isBlank()) {
                continue;
            }
            ObjectNode node = out.addObject();
            node.put("word", word);
            String pos = item.path("partOfSpeech").asString("");
            if (!pos.isBlank()) {
                node.put("pos", pos);
            }
            String nuance = item.path("nuance").asString("");
            if (!nuance.isBlank()) {
                node.put("nuance", nuance);
            }
        }
    }

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

    /**
     * 图片块（T594 第 9 块）：只有安全 URL 才输出；没有图或地址不安全时**整个字段不出现**，
     * 前端回退到无图版式（而不是渲染一张坏图）。
     */
    private static void putImage(ObjectNode view, JsonNode raw) {
        String url = text(raw.path("basicInfo"), "image");
        if (url == null || !isSafeImageUrl(url)) {
            return;
        }
        view.putObject("image").put("url", url);
    }

    /**
     * 图片地址白名单：只放行 {@code http(s)://} 绝对地址与以 {@code /} 开头的站内相对路径。
     *
     * <p>为什么要在读路径挡：内容由 AI 生成、也能被改写，{@code javascript:} / {@code data:} 一类
     * 地址一旦进了 {@code <img src>} 就是注入面；协议相对地址 {@code //host/x} 也不是站内路径，
     * 一并挡掉。含空白或控制字符的地址直接拒绝——真实图片地址不需要它们，放进来只会给解析留歧义。
     */
    static boolean isSafeImageUrl(String url) {
        if (url == null) {
            return false;
        }
        String value = url.strip();
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        String lowered = value.toLowerCase(java.util.Locale.ROOT);
        if (lowered.startsWith("https://") || lowered.startsWith("http://")) {
            String rest = value.substring(value.indexOf("://") + 3);
            return !rest.isEmpty() && !rest.startsWith("/");
        }
        return value.startsWith("/") && !value.startsWith("//");
    }

    /** block id → view 里承载它的字段名；payload 直接复用该字段的值，不新造形状。 */
    private static String payloadField(String blockId) {
        return switch (blockId) {
            case "core" -> "coreSemantics";
            // 其余 8 个块与 view 里的字段同名（senses/examples/synonyms/antonyms/family/
            // derivation/analysis/image）
            default -> blockId;
        };
    }

    /**
     * 输出有序的 {@code blocks} 数组（T594）。顺序 / 去重 / 未知 id / hidden / 缺 payload 的规则见
     * {@link #toView(JsonNode, List, List)}。
     *
     * <p>v2 内容即使一块都没有也留下 {@code blocks: []}：前端靠「数组在场」区分「后端按契约说没有」
     * 与「旧数据没有这个字段」两种情况。
     */
    private static void putBlocks(ObjectNode view, List<String> blockOrder, List<String> hidden) {
        ArrayNode blocks = view.putArray("blocks");
        Set<String> hiddenIds = new LinkedHashSet<>();
        if (hidden != null) {
            for (String id : hidden) {
                if (id != null) {
                    hiddenIds.add(id.strip());
                }
            }
        }
        List<String> order = blockOrder == null ? ReviewPrefs.ALL_BLOCKS : blockOrder;
        Set<String> seen = new LinkedHashSet<>();
        for (String id : order) {
            if (id == null) {
                continue;
            }
            String blockId = id.strip();
            if (!ReviewPrefs.ALL_BLOCKS.contains(blockId)) {
                // 未知 id（比如前端新版本多写了一个 key）忽略，不当错误：老后端不该因此 500。
                continue;
            }
            if (!seen.add(blockId)) {
                continue;
            }
            if (hiddenIds.contains(blockId)) {
                continue;
            }
            JsonNode payload = view.get(payloadField(blockId));
            if (payload == null || payload.isNull() || payload.isMissingNode()) {
                continue;
            }
            if ((payload.isArray() || payload.isObject()) && payload.size() == 0) {
                continue;
            }
            ObjectNode block = blocks.addObject();
            block.put("id", blockId);
            block.set("payload", payload);
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
