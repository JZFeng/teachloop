package com.teachloop.vocab.sets;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 词集持久化（T406 / US-49）：词集元数据、词条顺序与软删除。
 *
 * <p>只做数据访问，不做权限判定与字段校验——这些留在 {@link VocabularySetEndpoint}。
 * 词集归属由 `vocab_sets.teacher_id` 保证：所有写操作都带 `teacher_id` 条件，
 * 因此「不属于自己的词集」在 SQL 层就是 0 行，端点按 404 处理（AC-54.2）。
 *
 * <p>软删除：`deleted = true` 的行对列表与详情一律不可见，但保留词条关联，
 * 恢复时无需重建。
 */
@Repository
public class VocabularySetRepository {

    /** 词集视图的投影，list 与 find 共用同一份，避免两处漂移。 */
    private static final String SET_VIEW_SELECT = """
            select s.id, s.name, s.language, s.description, s.active, s.updated_at,
                   (select count(*) from vocab_set_words w where w.set_id = s.id) as word_count,
                   (select count(distinct ac.class_id) from vocab_assignments a
                      join vocab_assignment_classes ac on ac.assignment_id = a.id
                     where a.set_id = s.id) as assigned_classes
              from vocab_sets s
            """;

    private static final RowMapper<SetView> SET_VIEW_MAPPER = (rs, rowNumber) -> new SetView(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getBoolean(5), rs.getTimestamp(6).toInstant(), rs.getInt(7), rs.getInt(8));

    private static final RowMapper<SetWordView> SET_WORD_MAPPER = (rs, rowNumber) -> new SetWordView(
            rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4),
            rs.getObject(5) == null ? null : rs.getInt(5));

    private final JdbcTemplate jdbc;

    public VocabularySetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SetView(UUID id, String name, String language, String description, boolean active,
                          Instant updatedAt, int wordCount, int assignedClasses) {
    }

    public record SetWordView(UUID id, int position, String form, String normalizedForm, Integer band) {
    }

    public List<SetView> listByTeacher(UUID teacherId) {
        return jdbc.query(SET_VIEW_SELECT + """
                 where s.teacher_id = ? and s.deleted = false
                 order by s.updated_at desc
                """, SET_VIEW_MAPPER, teacherId);
    }

    public Optional<SetView> findOwned(UUID id, UUID teacherId) {
        return jdbc.query(SET_VIEW_SELECT + """
                 where s.id = ? and s.teacher_id = ? and s.deleted = false
                """, SET_VIEW_MAPPER, id, teacherId).stream().findFirst();
    }

    /** 新建词集；同名冲突由数据库唯一键抛出 {@code DuplicateKeyException}，由端点转 409。 */
    public void create(UUID id, UUID teacherId, String language, String name, String description,
                       Instant now) {
        jdbc.update("""
                insert into vocab_sets (id, teacher_id, language, name, description, active, deleted,
                                        created_at, updated_at)
                values (?, ?, ?, ?, ?, true, false, ?, ?)
                """, id, teacherId, language, name, description,
                Timestamp.from(now), Timestamp.from(now));
    }

    public void updateMeta(UUID id, UUID teacherId, String name, String language, String description,
                           boolean active, Instant now) {
        jdbc.update("""
                update vocab_sets set name = ?, language = ?, description = ?, active = ?, updated_at = ?
                 where id = ? and teacher_id = ?
                """, name, language, description, active, Timestamp.from(now), id, teacherId);
    }

    /** 单条软删除：已删除的行也计 1（调用方据此返回 200，重复删除不报错）。 */
    public int softDelete(UUID id, UUID teacherId, Instant now) {
        return jdbc.update("""
                update vocab_sets set deleted = true, updated_at = ? where id = ? and teacher_id = ?
                """, Timestamp.from(now), id, teacherId);
    }

    /** 批量软删除：只计「本次真正被删除」的行，已删除的返回 0（保持批量动作的计数语义）。 */
    public int softDeleteIfActive(UUID id, UUID teacherId, Instant now) {
        return jdbc.update("""
                update vocab_sets set deleted = true, updated_at = ?
                 where id = ? and teacher_id = ? and deleted = false
                """, Timestamp.from(now), id, teacherId);
    }

    /** 批量启停用；返回受影响行数（0 = 不属于自己或已删除）。 */
    public int setActive(UUID id, UUID teacherId, boolean active, Instant now) {
        return jdbc.update("""
                update vocab_sets set active = ?, updated_at = ?
                 where id = ? and teacher_id = ? and deleted = false
                """, active, Timestamp.from(now), id, teacherId);
    }

    public List<SetWordView> wordsOf(UUID setId) {
        return jdbc.query("""
                select id, position, form, normalized_form, band
                  from vocab_set_words where set_id = ? order by position
                """, SET_WORD_MAPPER, setId);
    }

    public int countWords(UUID setId) {
        return jdbc.queryForObject(
                "select count(*) from vocab_set_words where set_id = ?", Integer.class, setId);
    }

    public int maxPosition(UUID setId) {
        return jdbc.queryForObject(
                "select coalesce(max(position), 0) from vocab_set_words where set_id = ?",
                Integer.class, setId);
    }

    /** 追加一个词条；返回 0 表示该词已在集合中（唯一键冲突被忽略），调用方据此计 skipped。 */
    public int insertWord(UUID id, UUID setId, int position, String form, String normalizedForm,
                          Long familyId, Integer band, Instant now) {
        return jdbc.update("""
                insert into vocab_set_words
                    (id, set_id, position, form, normalized_form, content, family_id, band, created_at)
                values (?, ?, ?, ?, ?, null, ?, ?, ?)
                on conflict (set_id, normalized_form) do nothing
                """, id, setId, position, form, normalizedForm, familyId, band, Timestamp.from(now));
    }

    public int removeWord(UUID wordId, UUID setId) {
        return jdbc.update("delete from vocab_set_words where id = ? and set_id = ?", wordId, setId);
    }

    public int updateWordPosition(UUID wordId, UUID setId, int position) {
        return jdbc.update("update vocab_set_words set position = ? where id = ? and set_id = ?",
                position, wordId, setId);
    }

    /** 词集内容变动后刷新 updated_at（列表按它倒序）。 */
    public void touch(UUID setId, Instant now) {
        jdbc.update("update vocab_sets set updated_at = ? where id = ?", Timestamp.from(now), setId);
    }
}
