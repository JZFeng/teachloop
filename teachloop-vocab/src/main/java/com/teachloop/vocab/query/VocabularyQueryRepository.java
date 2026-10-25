package com.teachloop.vocab.query;

import com.teachloop.shared.error.ApiException;
import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.content.ContentView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 词库查询（T396 / US-46 / US-59）：筛选、搜索、排序、分页。
 *
 * <p>语义：同一类筛选内部取并集（多个来源 = 任一命中；多个标签 = 任一命中；
 * 多个状态 = 任一命中），不同类筛选取交集。卡组视图把保存的 criteria 与请求筛选叠加
 * （仍是交集），这样「先选卡组再搜索」不会互相覆盖。
 *
 * <p>排序在 SQL 里做（不是只排当前页），并追加 `w.id` 作为稳定次序，翻页不抖动。
 * 软删除的词一律排除；**暂停（removed）的词保留**，列表仍可搜到（AC-59.2）。
 *
 * <p>T450：学生词单筛选（`?list=`，可重复）也是「同一类内部并集」——多份词单 = 任一命中；
 * 与来源/标签/状态/收藏/暂停/到期取交集。词单必须属于本人且未删除，否则 404（不静默忽略）。
 */
@Repository
public class VocabularyQueryRepository {

    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final ContentStore contentStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public VocabularyQueryRepository(JdbcTemplate jdbc, ContentStore contentStore) {
        this.jdbc = jdbc;
        this.contentStore = contentStore;
    }

    public record WordQuery(UUID userId, String language, String search, List<String> states,
                            List<String> sources, List<String> tags, Boolean favorite, Boolean paused, Boolean due, UUID deckId,
                            List<UUID> listIds,
                            String sort, String direction, int page, int size) {
    }

    public record SourceItem(String kind, UUID batchId, UUID assignmentId, String label) {
    }

    public record WordItem(UUID id, String form, String language, Long contentId, JsonNode content,
                           String contentStatus, boolean favorite, List<String> tags, boolean paused,
                           String state, Instant dueAt, BigDecimal intervalDays, BigDecimal ease,
                           Instant createdAt, Instant updatedAt, boolean generating,
                           List<SourceItem> sources) {
    }

    public record WordPage(List<WordItem> items, long total, int page, int size) {
    }

    public WordPage query(WordQuery query) {
        if (query.size() <= 0 || query.size() > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("size must be 1.." + MAX_PAGE_SIZE);
        }
        int page = Math.max(1, query.page());
        String language = query.language() == null || query.language().isBlank() ? "en" : query.language();

        Set<String> states = new LinkedHashSet<>(nonNull(query.states()));
        List<String> sources = new ArrayList<>(nonNull(query.sources()));
        Set<String> tags = new LinkedHashSet<>(nonNull(query.tags()));
        Boolean favorite = query.favorite();
        if (query.deckId() != null) {
            JsonNode criteria = deckCriteria(query.userId(), query.deckId());
            addAll(states, criteria.path("states"));
            addAll(tags, criteria.path("tags"));
            for (JsonNode source : criteria.path("sources")) {
                if (source.isString()) {
                    sources.add(source.asString());
                }
            }
            if (criteria.path("favorite").isBoolean()) {
                favorite = criteria.path("favorite").asBoolean();
            }
        }

        List<String> where = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        where.add("w.user_id = ?");
        arguments.add(query.userId());
        where.add("w.language = ?");
        arguments.add(language);
        where.add("w.deleted = false");

        // T450 / US-69：按学生词单筛选（可重复参数，多份词单取并集），与其他筛选取交集。
        // 词单 id 必须都属于本人，否则 404——不静默忽略（AC-69.9 不泄漏存在性）。
        List<UUID> listIds = new ArrayList<>(new LinkedHashSet<>(nonNull(query.listIds())));
        if (!listIds.isEmpty()) {
            List<Object> ownedArguments = new ArrayList<>();
            ownedArguments.add(query.userId());
            ownedArguments.addAll(listIds);
            Long owned = jdbc.queryForObject(
                    "select count(*) from vocab_lists where user_id = ? and deleted = false"
                            + " and id in (" + placeholders(listIds.size()) + ")",
                    Long.class, ownedArguments.toArray());
            if (owned == null || owned != listIds.size()) {
                throw ApiException.notFound("word list not found");
            }
            where.add("exists (select 1 from vocab_list_items i where i.word_id = w.id"
                    + " and i.list_id in (" + placeholders(listIds.size()) + "))");
            arguments.addAll(listIds);
        }

        if (!states.isEmpty()) {
            where.add("p.state in (" + placeholders(states.size()) + ")");
            arguments.addAll(states);
        }
        if (favorite != null) {
            where.add("w.favorite = ?");
            arguments.add(favorite);
        }
        if (query.paused() != null) {
            where.add("w.removed = ?");
            arguments.add(query.paused());
        }
        if (Boolean.TRUE.equals(query.due())) {
            where.add("(p.due_at <= now() and w.removed = false)");
        }
        if (!tags.isEmpty()) {
            where.add("jsonb_exists_any(w.tags, array[" + placeholders(tags.size()) + "]::text[])");
            arguments.addAll(tags);
        }
        if (query.search() != null && !query.search().isBlank()) {
            String pattern = "%" + escapeLike(query.search().strip()) + "%";
            // T431：V063 删除 vocab_words.content 后，按释义搜索降级为只按正面词形搜索
            // （释义现在存在全局内容包/delta，无法在 PG 里 join；见 T431 报告）。
            where.add("w.form ilike ? escape '\\'");
            arguments.add(pattern);
        }
        if (!sources.isEmpty()) {
            List<String> sourceConditions = new ArrayList<>();
            for (String source : sources) {
                if ("SELF".equals(source)) {
                    sourceConditions.add("s.kind = 'SELF'");
                } else if (source.startsWith("IMPORT:")) {
                    sourceConditions.add("(s.kind = 'IMPORT' and s.batch_id = ?)");
                    arguments.add(parseUuid(source.substring("IMPORT:".length())));
                } else if (source.startsWith("ASSIGNMENT:")) {
                    sourceConditions.add("(s.kind = 'ASSIGNMENT' and s.assignment_id = ?)");
                    arguments.add(parseUuid(source.substring("ASSIGNMENT:".length())));
                } else {
                    throw ApiException.badRequest("unknown source filter: " + source);
                }
            }
            where.add("exists (select 1 from vocab_word_sources s where s.word_id = w.id and ("
                    + String.join(" or ", sourceConditions) + "))");
        }

        String whereSql = String.join(" and ", where);
        Long total = jdbc.queryForObject(
                "select count(*) from vocab_words w left join vocab_progress p on p.word_id = w.id"
                        + " where " + whereSql, Long.class, arguments.toArray());

        List<Object> pageArguments = new ArrayList<>(arguments);
        String orderBy = orderBy(query.sort(), query.direction());
        pageArguments.add(query.size());
        pageArguments.add((long) (page - 1) * query.size());
        List<WordItem> items = jdbc.query("""
                        select w.id, w.form, w.language, w.content_id, w.content_status,
                               w.favorite, cast(w.tags as text), w.removed, p.state, p.due_at,
                               p.interval_days, p.ease, w.created_at, w.updated_at,
                               exists (select 1 from vocab_generation_jobs j
                                        where j.subject_type = 'WORD' and j.subject_id = w.id
                                          and j.state in ('QUEUED', 'RUNNING')) as generating
                          from vocab_words w
                          left join vocab_progress p on p.word_id = w.id
                         where %s
                         order by %s
                         limit ? offset ?
                        """.formatted(whereSql, orderBy),
                (rs, rowNumber) -> new WordItem(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getObject(4, Long.class),
                        null,
                        rs.getString(5),
                        rs.getBoolean(6),
                        parseTags(rs.getString(7)),
                        rs.getBoolean(8),
                        rs.getString(9),
                        rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(),
                        rs.getBigDecimal(11),
                        rs.getBigDecimal(12),
                        rs.getTimestamp(13).toInstant(),
                        rs.getTimestamp(14).toInstant(),
                        rs.getBoolean(15),
                        List.of()),
                pageArguments.toArray());

        Map<UUID, List<SourceItem>> sourcesByWord = sourcesFor(items.stream().map(WordItem::id).toList());
        List<WordItem> enriched = items.stream()
                .map(item -> new WordItem(item.id(), item.form(), item.language(), item.contentId(),
                        ContentView.resolve(contentStore, item.contentId(), item.language(), item.form()),
                        item.contentStatus(), item.favorite(), item.tags(), item.paused(), item.state(),
                        item.dueAt(), item.intervalDays(), item.ease(), item.createdAt(),
                        item.updatedAt(), item.generating(),
                        sourcesByWord.getOrDefault(item.id(), List.of())))
                .toList();
        return new WordPage(enriched, total == null ? 0 : total, page, query.size());
    }

    private Map<UUID, List<SourceItem>> sourcesFor(List<UUID> wordIds) {
        Map<UUID, List<SourceItem>> grouped = new LinkedHashMap<>();
        if (wordIds.isEmpty()) {
            return grouped;
        }
        List<Object> arguments = new ArrayList<>(wordIds);
        jdbc.query("""
                        select word_id, kind, batch_id, assignment_id, label
                          from vocab_word_sources
                         where word_id in (%s)
                         order by id
                        """.formatted(placeholders(wordIds.size())),
                rs -> {
                    UUID wordId = rs.getObject(1, UUID.class);
                    grouped.computeIfAbsent(wordId, key -> new ArrayList<>()).add(new SourceItem(
                            rs.getString(2),
                            rs.getObject(3, UUID.class),
                            rs.getObject(4, UUID.class),
                            rs.getString(5)));
                }, arguments.toArray());
        return grouped;
    }

    private JsonNode deckCriteria(UUID userId, UUID deckId) {
        List<String> rows = jdbc.query(
                "select cast(criteria as text) from vocab_decks"
                        + " where id = ? and user_id = ? and deleted = false",
                (rs, rowNumber) -> rs.getString(1), deckId, userId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("deck not found");
        }
        return parseJson(rows.get(0));
    }

    private JsonNode parseJson(String json) {
        return json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
    }

    private List<String> parseTags(String json) {
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : parseJson(json)) {
            if (tag.isString()) {
                tags.add(tag.asString());
            }
        }
        return tags;
    }

    private static String orderBy(String sort, String direction) {
        String column = switch (sort == null || sort.isBlank() ? "dueAt" : sort) {
            case "form" -> "lower(w.form)";
            case "state" -> "p.state";
            case "createdAt" -> "w.created_at";
            case "updatedAt" -> "w.updated_at";
            case "intervalDays" -> "p.interval_days";
            case "ease" -> "p.ease";
            case "favorite" -> "w.favorite";
            case "dueAt" -> "p.due_at";
            default -> throw ApiException.badRequest("unknown sort: " + sort);
        };
        boolean descending = "desc".equalsIgnoreCase(direction);
        return column + (descending ? " desc nulls last" : " asc nulls last") + ", w.id asc";
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static void addAll(Set<String> target, JsonNode node) {
        for (JsonNode item : node) {
            if (item.isString()) {
                target.add(item.asString());
            }
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid uuid in source filter: " + value);
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
