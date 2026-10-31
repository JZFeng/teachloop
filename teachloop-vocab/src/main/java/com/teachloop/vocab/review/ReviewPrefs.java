package com.teachloop.vocab.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 背单词偏好（US-88 / T552，spec AC-88.2…AC-88.8）。
 *
 * <p>与 {@code vocab_user_prefs.table_columns} 的关键区别：那一列是「服务端不解释形状」的纯前端配置，
 * 这一份**必须被队列解释**（配额与策略直接决定今天出哪些卡），所以由服务端校验与归一化，
 * 非法值一律纠正而不是 500。
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@code dailyNewCards}：每天最多进队的新词数，clamp 到 3…硬上限；</li>
 *   <li>{@code strategy}：{@code REVIEW_FIRST / MIXED / NEW_FIRST}，缺省 {@code MIXED}（= 旧行为）；</li>
 *   <li>{@code blocks}：反面 9 块的**顺序**（不决定有无）；未知 key 丢弃、缺失 key 追加到末尾；</li>
 *   <li>{@code hidden}：被关掉的块。顺序与开关是两个系统，不能靠「不在 blocks 里」表达关闭。</li>
 * </ul>
 */
public record ReviewPrefs(int dailyNewCards, String strategy, List<String> blocks, List<String> hidden) {

    public static final String REVIEW_FIRST = "REVIEW_FIRST";
    public static final String MIXED = "MIXED";
    public static final String NEW_FIRST = "NEW_FIRST";

    /**
     * 反面 9 块：与前端 `wordBlocks.ts` 的 key 一一对应，**这是块 id 与默认顺序的唯一来源**
     * （{@code ContentView} 排 blocks 时直接读它，避免两处列表漂移）。
     *
     * <p>T594：第 9 位追加 {@code image}（词卡配图）。前 8 位的顺序一个字符都没动——存量用户
     * 存的 blocks 只有 8 个 key，{@link #normalizeBlocks} 的 {@code out.addAll(ALL_BLOCKS)}
     * 会把缺的 {@code image} 自动补到末尾，所以旧数据不需要迁移。
     */
    public static final List<String> ALL_BLOCKS = List.of(
            "core", "senses", "examples", "synonyms", "antonyms", "family", "derivation", "analysis",
            "image");

    private static final Set<String> STRATEGIES = Set.of(REVIEW_FIRST, MIXED, NEW_FIRST);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final int MIN_CARDS = 3;

    /**
     * 缺省值：与改造前完全一致（20 张、混合、全显示、默认顺序）。
     *
     * <p>这里**不套用 {@link #MIN_CARDS}**：这是运维给的默认值（测试里常用 2 来压缩用例），
     * 3 张的下限只约束「用户自己填的数」，不该把环境变量也一起抬上去。
     */
    public static ReviewPrefs defaults(int defaultCards) {
        return new ReviewPrefs(Math.max(0, defaultCards), MIXED, ALL_BLOCKS, List.of());
    }

    public boolean newFirst() {
        return NEW_FIRST.equals(strategy);
    }

    public boolean reviewFirst() {
        return REVIEW_FIRST.equals(strategy);
    }

    /**
     * 从库里存的 JSON 解析；任何字段缺失或非法都回落到缺省值，不抛异常
     * （这条列可能是旧版本前端写的，不能因此让复习页 500）。
     */
    public static ReviewPrefs parse(JsonNode node, int defaultCards, int maxCards) {
        ReviewPrefs fallback = defaults(defaultCards);
        if (node == null || node.isNull() || !node.isObject()) {
            return fallback;
        }
        int cards = node.has("dailyNewCards")
                ? clampCards(node.path("dailyNewCards").asInt(defaultCards), maxCards)
                : fallback.dailyNewCards();
        String strategy = normalizeStrategy(node.path("strategy").asString(MIXED));
        return new ReviewPrefs(cards, strategy,
                normalizeBlocks(node.path("blocks")), normalizeHidden(node.path("hidden")));
    }

    public static int clampCards(int value, int maxCards) {
        int max = Math.max(MIN_CARDS, maxCards);
        return Math.min(max, Math.max(MIN_CARDS, value));
    }

    public static String normalizeStrategy(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return STRATEGIES.contains(value) ? value : MIXED;
    }

    /** 顺序：已知 key 去重保序；未出现的 key 追加到末尾（保证 8 块永远齐）。 */
    public static List<String> normalizeBlocks(JsonNode raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw != null && raw.isArray()) {
            for (JsonNode key : raw) {
                String value = key.asString("").trim();
                if (ALL_BLOCKS.contains(value)) {
                    out.add(value);
                }
            }
        }
        out.addAll(ALL_BLOCKS);
        return new ArrayList<>(out);
    }

    public static List<String> normalizeHidden(JsonNode raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw != null && raw.isArray()) {
            for (JsonNode key : raw) {
                String value = key.asString("").trim();
                if (ALL_BLOCKS.contains(value)) {
                    out.add(value);
                }
            }
        }
        return List.copyOf(out);
    }

    public String toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("dailyNewCards", dailyNewCards);
        node.put("strategy", strategy);
        ArrayNode order = node.putArray("blocks");
        blocks.forEach(order::add);
        ArrayNode off = node.putArray("hidden");
        hidden.forEach(off::add);
        return MAPPER.writeValueAsString(node);
    }
}
