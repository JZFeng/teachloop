package com.teachloop.vocab.assign;

import com.teachloop.shared.error.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 发词记录持久化（T408 / T409 / T413 / US-50 / US-51）：词集快照、班级关联、收件人与领取状态。
 *
 * <p>发送时把词集内容**快照**进 `vocab_assignment_words`：之后改词集不影响已发的作业。
 * 收件人按发送时的班级成员快照写入 `vocab_assignment_recipients`，领取幂等（`claimed_at` 只写一次）。
 *
 * <p>只做数据访问；越权判定（班级归属走 {@code Ports.ClassMembership}）与通知发送留在
 * {@link VocabularyAssignmentEndpoint}。
 */
@Repository
public class VocabularyAssignmentRepository {

    private static final RowMapper<AssignmentView> ASSIGNMENT_VIEW_MAPPER =
            (rs, rowNumber) -> new AssignmentView(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                    rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(),
                    rs.getTimestamp(5).toInstant(), rs.getString(6),
                    rs.getInt(7), rs.getInt(8), rs.getInt(9));

    private static final RowMapper<PendingView> PENDING_VIEW_MAPPER = (rs, rowNumber) -> new PendingView(
            rs.getObject(1, UUID.class), rs.getString(2),
            rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
            rs.getString(4));

    private final JdbcTemplate jdbc;

    public VocabularyAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AssignmentView(UUID id, UUID setId, String setName, Instant dueAt, Instant createdAt,
                                 String note, int classCount, int recipientCount, int claimedCount) {
    }

    public record PendingView(UUID id, String setName, Instant dueAt, String note) {
    }

    public record PreviewWord(String form, Integer band) {
    }

    /** 可发布的词集（属于该教师、未删除、已启用）。 */
    public record ActiveSet(String name, String language) {
    }

    /** 发词快照的行：写入 `vocab_assignment_words` 需要 content 的 JSON 文本。 */
    public record SetWordSnapshot(int position, String form, String normalizedForm, String contentJson,
                                  Long familyId, Integer band) {
    }

    /** 领取时读回的词条快照。 */
    public record ClaimWord(String form, String contentJson, Long familyId, Integer band) {
    }

    /** 收件人视角的发词详情。 */
    public record RecipientView(String setName, Instant dueAt, String note, boolean claimed) {
    }

    /** 领取需要的归属信息：词的属主教师与语言。 */
    public record ClaimSource(UUID teacherId, String language) {
    }

    public Optional<ActiveSet> activeSet(UUID setId, UUID teacherId) {
        return jdbc.query("""
                select name, language from vocab_sets where id = ? and teacher_id = ?
                 and deleted = false and active = true
                """, (rs, rowNumber) -> new ActiveSet(rs.getString(1), rs.getString(2)),
                setId, teacherId).stream().findFirst();
    }

    public List<SetWordSnapshot> wordsOfSet(UUID setId) {
        return jdbc.query("""
                select position, form, normalized_form, cast(content as text), family_id, band
                  from vocab_set_words where set_id = ? order by position
                """, (rs, rowNumber) -> new SetWordSnapshot(
                        rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5) == null ? null : rs.getLong(5),
                        rs.getObject(6) == null ? null : rs.getInt(6)),
                setId);
    }

    public void createAssignment(UUID id, UUID setId, UUID teacherId, Instant dueAt, String note,
                                 Instant now) {
        jdbc.update("""
                insert into vocab_assignments (id, set_id, teacher_id, due_at, note, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, id, setId, teacherId,
                dueAt == null ? null : Timestamp.from(dueAt), note, Timestamp.from(now));
    }

    public void addAssignmentWord(UUID id, UUID assignmentId, SetWordSnapshot word, Instant now) {
        jdbc.update("""
                insert into vocab_assignment_words
                    (id, assignment_id, position, form, normalized_form, content, family_id, band, created_at)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
                """, id, assignmentId, word.position(), word.form(), word.normalizedForm(),
                word.contentJson(), word.familyId(), word.band(), Timestamp.from(now));
    }

    public void linkClass(UUID assignmentId, UUID classId) {
        jdbc.update("insert into vocab_assignment_classes (assignment_id, class_id) values (?, ?)",
                assignmentId, classId);
    }

    public void addRecipient(UUID assignmentId, UUID studentId) {
        jdbc.update("""
                insert into vocab_assignment_recipients (assignment_id, student_id)
                values (?, ?) on conflict do nothing
                """, assignmentId, studentId);
    }

    public List<AssignmentView> listByTeacher(UUID teacherId) {
        return jdbc.query("""
                select a.id, a.set_id, s.name, a.due_at, a.created_at, a.note,
                       (select count(*) from vocab_assignment_classes c where c.assignment_id = a.id),
                       (select count(*) from vocab_assignment_recipients r where r.assignment_id = a.id),
                       (select count(*) from vocab_assignment_recipients r
                         where r.assignment_id = a.id and r.claimed_at is not null)
                  from vocab_assignments a
                  join vocab_sets s on s.id = a.set_id
                 where a.teacher_id = ?
                 order by a.created_at desc
                """, ASSIGNMENT_VIEW_MAPPER, teacherId);
    }

    public List<PendingView> pendingForStudent(UUID studentId) {
        return jdbc.query("""
                select a.id, s.name, a.due_at, a.note
                  from vocab_assignment_recipients r
                  join vocab_assignments a on a.id = r.assignment_id
                  join vocab_sets s on s.id = a.set_id
                 where r.student_id = ? and r.claimed_at is null
                   and (a.due_at is null or a.due_at > now())
                 order by a.created_at desc
                """, PENDING_VIEW_MAPPER, studentId);
    }

    public Optional<RecipientView> recipientOf(UUID assignmentId, UUID studentId) {
        return jdbc.query("""
                select s.name, a.due_at, a.note, r.claimed_at
                  from vocab_assignment_recipients r
                  join vocab_assignments a on a.id = r.assignment_id
                  join vocab_sets s on s.id = a.set_id
                 where r.assignment_id = ? and r.student_id = ?
                """, (rs, rowNumber) -> new RecipientView(
                        rs.getString(1),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                        rs.getString(3), rs.getTimestamp(4) != null),
                assignmentId, studentId).stream().findFirst();
    }

    public List<PreviewWord> previewWords(UUID assignmentId) {
        return jdbc.query("""
                select form, band from vocab_assignment_words
                 where assignment_id = ? order by position
                """, (rs, rowNumber) -> new PreviewWord(
                        rs.getString(1), rs.getObject(2) == null ? null : rs.getInt(2)),
                assignmentId);
    }

    public Optional<ClaimSource> claimSource(UUID assignmentId, UUID studentId) {
        return jdbc.query("""
                select a.teacher_id, s.language
                  from vocab_assignment_recipients r
                  join vocab_assignments a on a.id = r.assignment_id
                  join vocab_sets s on s.id = a.set_id
                 where r.assignment_id = ? and r.student_id = ?
                """, (rs, rowNumber) -> new ClaimSource(
                        rs.getObject(1, UUID.class), rs.getString(2)),
                assignmentId, studentId).stream().findFirst();
    }

    public List<ClaimWord> assignmentWords(UUID assignmentId) {
        return jdbc.query("""
                select form, cast(content as text), family_id, band
                  from vocab_assignment_words where assignment_id = ? order by position
                """, (rs, rowNumber) -> new ClaimWord(
                        rs.getString(1), rs.getString(2),
                        rs.getObject(3) == null ? null : rs.getLong(3),
                        rs.getObject(4) == null ? null : rs.getInt(4)),
                assignmentId);
    }

    public void markClaimed(UUID assignmentId, UUID studentId, Instant now) {
        jdbc.update("update vocab_assignment_recipients set claimed_at = ?"
                + " where assignment_id = ? and student_id = ?", Timestamp.from(now), assignmentId, studentId);
    }

    /** 发词后刷新词集的 updated_at（词集列表按它倒序）。 */
    public void touchSet(UUID setId, Instant now) {
        jdbc.update("update vocab_sets set updated_at = ? where id = ?", Timestamp.from(now), setId);
    }

    /** 收件人不存在时抛 404；存在性检查与详情读取共用一条 SQL。 */
    public RecipientView requireRecipient(UUID assignmentId, UUID studentId) {
        return recipientOf(assignmentId, studentId)
                .orElseThrow(() -> ApiException.notFound("assignment not found"));
    }

    /**
     * 这次布置属于哪位教师。
     *
     * <p>只用于「老师点开自己复制出去的学生链接」时给一句人话：不返回收件人信息，
     * 也不让调用方拿到别人的布置。
     */
    public Optional<UUID> teacherOf(UUID assignmentId) {
        return jdbc.query("select teacher_id from vocab_assignments where id = ?",
                (rs, rowNumber) -> rs.getObject(1, UUID.class), assignmentId).stream().findFirst();
    }
}
