package com.teachloop.vocab.sets;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.lexicon.WordFamily;
import com.teachloop.vocab.lexicon.WordFamilyIndex;
import com.teachloop.vocab.word.WordNormalizer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师词集后端（T406 / US-49）。词集是教师侧内容资产：一条词集可反复布置，
 * 词条顺序即学生首次预习顺序；「移除」只把词移出词集，不删词条、不影响学生词库。
 *
 * <p>只允许 TEACHER / ADMIN；不属于自己的词集按 404 处理（AC-54.2）。
 */
@RestController
@RequestMapping("/api/vocab/sets")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class VocabularySetEndpoint {

    private static final int MAX_BATCH = 200;

    private final JdbcTemplate jdbc;
    private final WordFamilyIndex lexicon;
    private final Clock clock;
    private final int maxWordsPerSet;

    public VocabularySetEndpoint(JdbcTemplate jdbc, WordFamilyIndex lexicon, Clock clock,
                                 @Value("${teachloop.vocab.sets.max-words:2000}") int maxWordsPerSet) {
        this.jdbc = jdbc;
        this.lexicon = lexicon;
        this.clock = clock;
        this.maxWordsPerSet = Math.max(1, maxWordsPerSet);
    }

    public record SetView(UUID id, String name, String language, String description, boolean active,
                          Instant updatedAt, int wordCount, int assignedClasses) {
    }

    public record SetWordView(UUID id, int position, String form, String normalizedForm, Integer band) {
    }

    public record SetDetail(SetView set, List<SetWordView> words) {
    }

    public record CreateRequest(String name, String language, String description) {
    }

    public record UpdateRequest(String name, String language, String description, Boolean active) {
    }

    public record BatchRequest(List<UUID> ids, String action) {
    }

    public record AddWordsRequest(List<String> words) {
    }

    public record AddWordsResult(int added, int skipped) {
    }

    public record OrderRequest(List<UUID> wordIds) {
    }

    public record Affected(int affected) {
    }

    @GetMapping
    public List<SetView> list(@RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        return jdbc.query("""
                select s.id, s.name, s.language, s.description, s.active, s.updated_at,
                       (select count(*) from vocab_set_words w where w.set_id = s.id) as word_count,
                       (select count(distinct ac.class_id) from vocab_assignments a
                          join vocab_assignment_classes ac on ac.assignment_id = a.id
                         where a.set_id = s.id) as assigned_classes
                  from vocab_sets s
                 where s.teacher_id = ? and s.deleted = false
                 order by s.updated_at desc
                """, (rs, rowNumber) -> new SetView(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBoolean(5), rs.getTimestamp(6).toInstant(), rs.getInt(7), rs.getInt(8)),
                me.id());
    }

    @PostMapping
    public SetView create(@RequestBody(required = false) CreateRequest request,
                          @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        String name = validName(request == null ? null : request.name());
        String language = request == null || request.language() == null || request.language().isBlank()
                ? "en" : request.language();
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            jdbc.update("""
                    insert into vocab_sets (id, teacher_id, language, name, description, active, deleted,
                                            created_at, updated_at)
                    values (?, ?, ?, ?, ?, true, false, ?, ?)
                    """, id, me.id(), language, name,
                    request == null ? null : request.description(),
                    Timestamp.from(now), Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw ApiException.conflict("a word set with this name already exists");
        }
        return find(id, me.id());
    }

    @GetMapping("/{id}")
    public SetDetail detail(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        SetView set = find(id, me.id());
        List<SetWordView> words = jdbc.query("""
                select id, position, form, normalized_form, band
                  from vocab_set_words where set_id = ? order by position
                """, (rs, rowNumber) -> new SetWordView(
                rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getObject(5) == null ? null : rs.getInt(5)),
                id);
        return new SetDetail(set, words);
    }

    @PutMapping("/{id}")
    public SetView update(@PathVariable UUID id, @RequestBody(required = false) UpdateRequest request,
                          @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        SetView current = find(id, me.id());
        String name = request == null || request.name() == null ? current.name() : validName(request.name());
        String language = request == null || request.language() == null || request.language().isBlank()
                ? current.language() : request.language();
        String description = request == null || request.description() == null
                ? current.description() : request.description();
        boolean active = request == null || request.active() == null ? current.active() : request.active();
        try {
            jdbc.update("""
                    update vocab_sets set name = ?, language = ?, description = ?, active = ?, updated_at = ?
                     where id = ? and teacher_id = ?
                    """, name, language, description, active, Timestamp.from(clock.instant()),
                    id, me.id());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw ApiException.conflict("a word set with this name already exists");
        }
        return find(id, me.id());
    }

    @DeleteMapping("/{id}")
    public Affected delete(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        int updated = jdbc.update("""
                update vocab_sets set deleted = true, updated_at = ? where id = ? and teacher_id = ?
                """, Timestamp.from(clock.instant()), id, me.id());
        if (updated == 0) {
            throw ApiException.notFound("word set not found");
        }
        return new Affected(updated);
    }

    @PostMapping("/batch")
    public Affected batch(@RequestBody(required = false) BatchRequest request,
                          @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw ApiException.badRequest("ids is required");
        }
        if (request.ids().size() > MAX_BATCH) {
            throw ApiException.badRequest("at most " + MAX_BATCH + " ids per batch");
        }
        int affected = 0;
        for (UUID id : request.ids()) {
            affected += switch (request.action() == null ? "" : request.action()) {
                case "ACTIVATE" -> jdbc.update("update vocab_sets set active = true, updated_at = ?"
                        + " where id = ? and teacher_id = ? and deleted = false",
                        Timestamp.from(clock.instant()), id, me.id());
                case "DEACTIVATE" -> jdbc.update("update vocab_sets set active = false, updated_at = ?"
                        + " where id = ? and teacher_id = ? and deleted = false",
                        Timestamp.from(clock.instant()), id, me.id());
                case "DELETE" -> jdbc.update("update vocab_sets set deleted = true, updated_at = ?"
                        + " where id = ? and teacher_id = ? and deleted = false",
                        Timestamp.from(clock.instant()), id, me.id());
                default -> throw ApiException.badRequest("action must be ACTIVATE, DEACTIVATE or DELETE");
            };
        }
        return new Affected(affected);
    }

    @PostMapping("/{id}/words")
    @Transactional
    public AddWordsResult addWords(@PathVariable UUID id, @RequestBody(required = false) AddWordsRequest request,
                                   @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        find(id, me.id());
        int existing = jdbc.queryForObject(
                "select count(*) from vocab_set_words where set_id = ?", Integer.class, id);
        int position = jdbc.queryForObject(
                "select coalesce(max(position), 0) from vocab_set_words where set_id = ?", Integer.class, id);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int added = 0;
        int skipped = 0;
        for (String raw : request == null ? List.<String>of() : request.words()) {
            if (raw == null || raw.isBlank()) {
                skipped++;
                continue;
            }
            String form = WordNormalizer.display(raw);
            String normalized = WordNormalizer.normalized(form);
            if (!WordNormalizer.valid(form) || !seen.add(normalized)) {
                skipped++;
                continue;
            }
            if (existing + added >= maxWordsPerSet) {
                skipped++;
                continue;
            }
            WordFamily family = lexicon.lookup(form).orElse(null);
            int inserted = jdbc.update("""
                    insert into vocab_set_words
                        (id, set_id, position, form, normalized_form, content, family_id, band, created_at)
                    values (?, ?, ?, ?, ?, null, ?, ?, ?)
                    on conflict (set_id, normalized_form) do nothing
                    """, UUID.randomUUID(), id, ++position, form, normalized,
                    family == null ? null : family.familyId(),
                    family == null ? null : family.band(), Timestamp.from(clock.instant()));
            if (inserted == 0) {
                skipped++;
            } else {
                added++;
            }
        }
        touch(id);
        return new AddWordsResult(added, skipped);
    }

    @DeleteMapping("/{id}/words/{wordId}")
    public Affected removeWord(@PathVariable UUID id, @PathVariable UUID wordId,
                               @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        find(id, me.id());
        int removed = jdbc.update("delete from vocab_set_words where id = ? and set_id = ?", wordId, id);
        if (removed == 0) {
            throw ApiException.notFound("word not in this set");
        }
        touch(id);
        return new Affected(removed);
    }

    @PutMapping("/{id}/words/order")
    @Transactional
    public Affected reorder(@PathVariable UUID id, @RequestBody(required = false) OrderRequest request,
                            @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        find(id, me.id());
        int position = 0;
        for (UUID wordId : request == null || request.wordIds() == null ? List.<UUID>of() : request.wordIds()) {
            position++;
            jdbc.update("update vocab_set_words set position = ? where id = ? and set_id = ?",
                    position, wordId, id);
        }
        touch(id);
        return new Affected(position);
    }

    private SetView find(UUID id, UUID teacherId) {
        List<SetView> rows = jdbc.query("""
                select s.id, s.name, s.language, s.description, s.active, s.updated_at,
                       (select count(*) from vocab_set_words w where w.set_id = s.id),
                       (select count(distinct ac.class_id) from vocab_assignments a
                          join vocab_assignment_classes ac on ac.assignment_id = a.id
                         where a.set_id = s.id)
                  from vocab_sets s
                 where s.id = ? and s.teacher_id = ? and s.deleted = false
                """, (rs, rowNumber) -> new SetView(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBoolean(5), rs.getTimestamp(6).toInstant(), rs.getInt(7), rs.getInt(8)),
                id, teacherId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("word set not found");
        }
        return rows.get(0);
    }

    private void touch(UUID id) {
        jdbc.update("update vocab_sets set updated_at = ? where id = ?",
                Timestamp.from(clock.instant()), id);
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 100) {
            throw ApiException.badRequest("name is required (<=100 chars)");
        }
        return name;
    }

    private static void requireTeacher(CurrentUser me) {
        if (!me.is("TEACHER") && !me.is("ADMIN")) {
            throw ApiException.forbidden("teacher only");
        }
    }
}
