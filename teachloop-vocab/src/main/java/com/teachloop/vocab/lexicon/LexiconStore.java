package com.teachloop.vocab.lexicon;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 词表索引的持久化（T388 / plan §3.1 方案 B）。
 *
 * <p>查询永远走内存（{@link WordFamilyIndex}），这两张表只做持久化与运维侧可查。
 * 装载按 manifest 的 {@code content_sha256} 判断：指纹没变就跳过写库。
 *
 * <p>重建用 upsert 而不是「删表重灌」：族 id 是内容哈希，词表更新时未变化的族
 * 保持同一个 id，{@code vocab_words.family_id} 不会指错族；只有真的消失的族才删除
 * （外键是 {@code on delete set null}）。
 */
@Repository
public class LexiconStore {

    private static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbc;

    public LexiconStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppliedManifest> applied() {
        List<AppliedManifest> rows = jdbc.query("""
                select content_sha256, family_count, form_count, applied_at
                  from vocab_lexicon_meta
                 where id = 1
                """, (rs, rowNumber) -> new AppliedManifest(
                rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getTimestamp(4).toInstant()));
        return rows.stream().findFirst();
    }

    @Transactional
    public void apply(LexiconData data, Instant now) {
        Timestamp appliedAt = Timestamp.from(now);

        jdbc.execute("create temp table tmp_vocab_lexicon_families (id bigint primary key) on commit drop");
        jdbc.batchUpdate("insert into tmp_vocab_lexicon_families (id) values (?)",
                data.families().stream().map(family -> new Object[]{family.id()}).toList());
        jdbc.batchUpdate("""
                        insert into vocab_lexicon_families (id, head, band, family_freq, created_at)
                        values (?, ?, ?, ?, ?)
                        on conflict (id) do update
                           set head = excluded.head,
                               band = excluded.band,
                               family_freq = excluded.family_freq
                        """,
                data.families(), BATCH_SIZE, (ps, family) -> {
                    ps.setLong(1, family.id());
                    ps.setString(2, family.head());
                    ps.setInt(3, family.band());
                    ps.setLong(4, family.familyFreq());
                    ps.setTimestamp(5, appliedAt);
                });

        jdbc.execute("create temp table tmp_vocab_lexicon_forms (normalized_form varchar(128) primary key)"
                + " on commit drop");
        jdbc.batchUpdate("insert into tmp_vocab_lexicon_forms (normalized_form) values (?)",
                data.forms().stream().map(form -> new Object[]{form.normalizedForm()}).toList());
        jdbc.batchUpdate("""
                        insert into vocab_lexicon_forms
                            (normalized_form, folded_form, family_id, form_freq, is_head, created_at)
                        values (?, ?, ?, ?, ?, ?)
                        on conflict (normalized_form) do update
                           set folded_form = excluded.folded_form,
                               family_id = excluded.family_id,
                               form_freq = excluded.form_freq,
                               is_head = excluded.is_head
                        """,
                data.forms(), BATCH_SIZE, (ps, form) -> {
                    ps.setString(1, form.normalizedForm());
                    ps.setString(2, form.foldedForm());
                    ps.setLong(3, form.familyId());
                    ps.setLong(4, form.formFreq());
                    ps.setBoolean(5, form.head());
                    ps.setTimestamp(6, appliedAt);
                });

        jdbc.update("""
                delete from vocab_lexicon_forms f
                 where not exists (select 1 from tmp_vocab_lexicon_forms t
                                    where t.normalized_form = f.normalized_form)
                """);
        jdbc.update("""
                delete from vocab_lexicon_families f
                 where not exists (select 1 from tmp_vocab_lexicon_families t
                                    where t.id = f.id)
                """);
        jdbc.update("""
                        insert into vocab_lexicon_meta
                            (id, content_sha256, family_count, form_count, applied_at)
                        values (1, ?, ?, ?, ?)
                        on conflict (id) do update
                           set content_sha256 = excluded.content_sha256,
                               family_count = excluded.family_count,
                               form_count = excluded.form_count,
                               applied_at = excluded.applied_at
                        """,
                data.manifest().contentSha256(), data.familyCount(), data.formCount(), appliedAt);
    }

    public long familyCount() {
        return count("vocab_lexicon_families");
    }

    public long formCount() {
        return count("vocab_lexicon_forms");
    }

    private long count(String table) {
        Long count = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return count == null ? 0 : count;
    }
}
