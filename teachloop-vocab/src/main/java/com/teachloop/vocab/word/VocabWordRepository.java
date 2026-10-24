package com.teachloop.vocab.word;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.teachloop.shared.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

/**
 * 学生词库的写入与去重查询（T393）。
 *
 * <p>词条唯一性由 `vocab_words (user_id, language, normalized_form)` 保证；这里先查后写，
 * 与数据库唯一键一起防并发重复。软删除的词**仍参与去重**：再次导入时恢复原词条
 * （AC-55.3），不新建、不重置进度。
 */
@Repository
public class VocabWordRepository {

    private static final int IN_CHUNK = 500;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public VocabWordRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record ExistingWord(UUID id, String form, boolean deleted) {
    }

    public Optional<ExistingWord> find(UUID userId, String language, String normalizedForm) {
        List<ExistingWord> rows = jdbc.query("""
                select id, form, deleted
                  from vocab_words
                 where user_id = ? and language = ? and normalized_form = ?
                 limit 1
                """, (rs, rowNumber) -> new ExistingWord(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3)),
                userId, language, normalizedForm);
        return rows.stream().findFirst();
    }

    /** 预览用：这批词形里哪些已经在库（含软删除）。 */
    public Set<String> existingNormalized(UUID userId, String language, Collection<String> normalizedForms) {
        Set<String> found = new HashSet<>();
        List<String> forms = new ArrayList<>(normalizedForms);
        for (int start = 0; start < forms.size(); start += IN_CHUNK) {
            List<String> chunk = forms.subList(start, Math.min(start + IN_CHUNK, forms.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> arguments = new ArrayList<>(chunk.size() + 2);
            arguments.add(userId);
            arguments.add(language);
            arguments.addAll(chunk);
            jdbc.query("select normalized_form from vocab_words"
                            + " where user_id = ? and language = ? and normalized_form in (" + placeholders + ")",
                    rs -> {
                        found.add(rs.getString(1));
                    }, arguments.toArray());
        }
        return found;
    }

    /** 新词入库：词条 PENDING + 一条 NEW 进度（当天进队列）。 */
    public UUID insert(UUID userId, String language, String form, String normalizedForm,
                       Long familyId, Integer band) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                        insert into vocab_words
                            (id, user_id, language, form, normalized_form, content_status,
                             family_id, band, removed, deleted, created_at, updated_at)
                        values (?, ?, ?, ?, ?, 'PENDING', ?, ?, false, false, ?, ?)
                        """,
                id, userId, language, form, normalizedForm, familyId, band, timestamp, timestamp);
        jdbc.update("""
                        insert into vocab_progress
                            (word_id, user_id, state, due_at, interval_days, ease,
                             lapses, reviewed_count, updated_at)
                        values (?, ?, 'NEW', ?, 0, 2.50, 0, 0, ?)
                        """,
                id, userId, timestamp, timestamp);
        return id;
    }

    /** 软删除恢复：词条重新可见，进度原样保留（AC-55.3）。 */
    public void restore(UUID wordId) {
        jdbc.update("update vocab_words set deleted = false, deleted_at = null, updated_at = ? where id = ?",
                Timestamp.from(clock.instant()), wordId);
    }

    /** 暂停复习：词仍在库、可搜索，只是不排期（AC-59.2）。 */
    public boolean pause(UUID userId, UUID wordId) {
        Instant now = clock.instant();
        return jdbc.update("update vocab_words set removed = true, removed_at = ?, updated_at = ?"
                        + " where id = ? and user_id = ?",
                Timestamp.from(now), Timestamp.from(now), wordId, userId) > 0;
    }

    /** 恢复复习：回到「新词」，当天进队列；历史日志保留（AC-59.3）。 */
    @Transactional
    public boolean resume(UUID userId, UUID wordId) {
        Timestamp timestamp = Timestamp.from(clock.instant());
        int updated = jdbc.update("""
                update vocab_words
                   set removed = false, removed_at = null, updated_at = ?
                 where id = ? and user_id = ?
                """, timestamp, wordId, userId);
        if (updated == 0) {
            return false;
        }
        jdbc.update("""
                update vocab_progress
                   set state = 'NEW', due_at = ?, interval_days = 0, ease = 2.50, updated_at = ?
                 where word_id = ?
                """, timestamp, timestamp, wordId);
        return true;
    }

    /** 软删除：列表/搜索/队列/统计都不可见；历史日志保留（AC-59.11）。 */
    public boolean delete(UUID userId, UUID wordId) {
        Instant now = clock.instant();
        return jdbc.update("update vocab_words set deleted = true, deleted_at = ?, updated_at = ?"
                        + " where id = ? and user_id = ?",
                Timestamp.from(now), Timestamp.from(now), wordId, userId) > 0;
    }

    public int batch(UUID userId, Collection<UUID> wordIds, String action) {
        int affected = 0;
        for (UUID wordId : wordIds) {
            affected += switch (action) {
                case "PAUSE" -> pause(userId, wordId) ? 1 : 0;
                case "DELETE" -> delete(userId, wordId) ? 1 : 0;
                default -> throw ApiException.badRequest("unknown batch action");
            };
        }
        return affected;
    }

    public record Stats(long total, long dueToday, long newWords, long mastered, long paused) {
    }

    public Stats stats(UUID userId, String language) {
        return jdbc.queryForObject("""
                select count(*) as total,
                       count(*) filter (where p.due_at <= now() and w.removed = false) as due_today,
                       count(*) filter (where p.state = 'NEW') as new_words,
                       count(*) filter (where p.state = 'GRADUATED') as mastered,
                       count(*) filter (where w.removed) as paused
                  from vocab_words w
                  join vocab_progress p on p.word_id = w.id
                 where w.user_id = ? and w.language = ? and w.deleted = false
                """, (rs, rowNumber) -> new Stats(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
                userId, language);
    }

    public String prefs(UUID userId) {
        List<String> rows = jdbc.query(
                "select cast(table_columns as text) from vocab_user_prefs where user_id = ?",
                (rs, rowNumber) -> rs.getString(1), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void savePrefs(UUID userId, String tableColumnsJson) {
        jdbc.update("""
                insert into vocab_user_prefs (user_id, table_columns, updated_at)
                values (?, cast(? as jsonb), ?)
                on conflict (user_id) do update
                   set table_columns = excluded.table_columns, updated_at = excluded.updated_at
                """, userId, tableColumnsJson, Timestamp.from(clock.instant()));
    }

    public record SourceOption(String kind, UUID batchId, UUID assignmentId, String label, long words) {
    }

    /** 来源筛选项：按批次/布置聚合，带词数（来源多选弹窗用）。 */
    public List<SourceOption> sources(UUID userId, String language) {
        return jdbc.query("""
                select s.kind, s.batch_id, s.assignment_id, s.label, count(distinct w.id) as words
                  from vocab_word_sources s
                  join vocab_words w on w.id = s.word_id
                 where w.user_id = ? and w.language = ? and w.deleted = false
                 group by s.kind, s.batch_id, s.assignment_id, s.label
                 order by max(s.id) desc
                """, (rs, rowNumber) -> new SourceOption(
                rs.getString(1),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getLong(5)),
                userId, language);
    }

    /** 追加来源；同一批次内重复调用由唯一键兜底（on conflict do nothing）。 */
    public void addImportSource(UUID wordId, UUID batchId, String label) {
        jdbc.update("""
                        insert into vocab_word_sources
                            (word_id, kind, batch_id, label, created_at)
                        values (?, 'IMPORT', ?, ?, ?)
                        on conflict do nothing
                        """,
                wordId, batchId, label, Timestamp.from(clock.instant()));
    }
}
