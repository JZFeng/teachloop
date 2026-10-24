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
import org.springframework.jdbc.core.JdbcTemplate;
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
