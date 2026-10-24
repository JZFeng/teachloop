package com.teachloop.vocab.assign;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.generation.VocabularyGenerationService;
import com.teachloop.vocab.word.VocabWordRepository;
import com.teachloop.vocab.word.WordNormalizer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师发词与学生领取（T408 / US-50 / US-51）。
 *
 * <p>发送时对词集内容做快照（之后改词集不影响已发）；收件人按发送时的班级成员快照。
 * 只能发自己带的班（不属于自己的班级 404，不泄露存在性）；通知失败不回滚发词。
 * 学生领取幂等：已在库的词只追加来源，不新建、不重置进度。
 */
@RestController
@RequestMapping("/api/vocab/assignments")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class VocabularyAssignmentEndpoint {

    private static final Logger log = LoggerFactory.getLogger(VocabularyAssignmentEndpoint.class);

    private final JdbcTemplate jdbc;
    private final Ports.ClassMembership classes;
    private final Ports.NotificationPort notifications;
    private final VocabWordRepository words;
    private final VocabularyGenerationService generation;
    private final Clock clock;

    public VocabularyAssignmentEndpoint(JdbcTemplate jdbc, Ports.ClassMembership classes,
                                        Ports.NotificationPort notifications,
                                        VocabWordRepository words,
                                        VocabularyGenerationService generation, Clock clock) {
        this.jdbc = jdbc;
        this.classes = classes;
        this.notifications = notifications;
        this.words = words;
        this.generation = generation;
        this.clock = clock;
    }

    public record CreateRequest(UUID setId, List<UUID> classIds, Instant dueAt, String note) {
    }

    public record Created(UUID id, int wordCount, int recipientCount) {
    }

    public record PendingView(UUID id, String setName, Instant dueAt, String note) {
    }

    public record ClaimResult(UUID assignmentId, int added, int duplicated) {
    }

    @PostMapping
    @Transactional
    public Created create(@RequestBody(required = false) CreateRequest request,
                          @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        if (request == null || request.setId() == null || request.classIds() == null
                || request.classIds().isEmpty()) {
            throw ApiException.badRequest("setId and classIds are required");
        }
        List<UUID> classIds = new ArrayList<>(new HashSet<>(request.classIds()));
        Set<UUID> owned = classes.classIdsOwnedBy(classIds, me.id());
        if (owned.size() != classIds.size()) {
            throw ApiException.notFound("class not found");
        }

        List<Map<String, Object>> setRows = jdbc.queryForList(
                "select name, language from vocab_sets where id = ? and teacher_id = ?"
                        + " and deleted = false and active = true", request.setId(), me.id());
        if (setRows.isEmpty()) {
            throw ApiException.notFound("word set not found");
        }
        String language = String.valueOf(setRows.get(0).get("language"));

        List<Map<String, Object>> setWords = jdbc.queryForList("""
                select position, form, normalized_form, content, family_id, band
                  from vocab_set_words where set_id = ? order by position
                """, request.setId());
        if (setWords.isEmpty()) {
            throw ApiException.badRequest("word set is empty");
        }

        UUID assignmentId = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                insert into vocab_assignments (id, set_id, teacher_id, due_at, note, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, assignmentId, request.setId(), me.id(),
                request.dueAt() == null ? null : Timestamp.from(request.dueAt()),
                request.note(), Timestamp.from(now));

        for (Map<String, Object> word : setWords) {
            jdbc.update("""
                    insert into vocab_assignment_words
                        (id, assignment_id, position, form, normalized_form, content, family_id, band, created_at)
                    values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
                    """, UUID.randomUUID(), assignmentId, word.get("position"), word.get("form"),
                    word.get("normalized_form"), word.get("content"), word.get("family_id"),
                    word.get("band"), Timestamp.from(now));
        }

        List<UUID> recipients = new ArrayList<>();
        for (UUID classId : classIds) {
            jdbc.update("insert into vocab_assignment_classes (assignment_id, class_id) values (?, ?)",
                    assignmentId, classId);
            for (UUID studentId : classes.membersOf(classId)) {
                recipients.add(studentId);
            }
        }
        Set<UUID> uniqueRecipients = new HashSet<>(recipients);
        for (UUID studentId : uniqueRecipients) {
            jdbc.update("""
                    insert into vocab_assignment_recipients (assignment_id, student_id)
                    values (?, ?) on conflict do nothing
                    """, assignmentId, studentId);
        }

        notifyStudents(assignmentId, uniqueRecipients);
        jdbc.update("update vocab_sets set updated_at = ? where id = ?", Timestamp.from(now), request.setId());
        return new Created(assignmentId, setWords.size(), uniqueRecipients.size());
    }

    public record AssignmentView(UUID id, UUID setId, String setName, Instant dueAt, Instant createdAt,
                                 String note, int classCount, int recipientCount, int claimedCount) {
    }

    /** T409：教师发词记录，按时间倒序；领取情况是实发收件人与 claimed_at 的实时聚合。 */
    @GetMapping
    public List<AssignmentView> list(@RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
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
                """, (rs, rowNumber) -> new AssignmentView(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5).toInstant(), rs.getString(6),
                rs.getInt(7), rs.getInt(8), rs.getInt(9)),
                me.id());
    }

    @GetMapping("/pending")
    public List<PendingView> pending(@RequestAttribute("currentUser") CurrentUser me) {
        return jdbc.query("""
                select a.id, s.name, a.due_at, a.note
                  from vocab_assignment_recipients r
                  join vocab_assignments a on a.id = r.assignment_id
                  join vocab_sets s on s.id = a.set_id
                 where r.student_id = ? and r.claimed_at is null
                   and (a.due_at is null or a.due_at > now())
                 order by a.created_at desc
                """, (rs, rowNumber) -> new PendingView(
                rs.getObject(1, UUID.class), rs.getString(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                rs.getString(4)),
                me.id());
    }

    @PostMapping("/{id}/claim")
    @Transactional
    public ClaimResult claim(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        List<Map<String, Object>> recipient = jdbc.queryForList("""
                select a.teacher_id, s.language
                  from vocab_assignment_recipients r
                  join vocab_assignments a on a.id = r.assignment_id
                  join vocab_sets s on s.id = a.set_id
                 where r.assignment_id = ? and r.student_id = ?
                """, id, me.id());
        if (recipient.isEmpty()) {
            throw ApiException.notFound("assignment not found");
        }
        UUID teacherId = (UUID) recipient.get(0).get("teacher_id");
        String language = String.valueOf(recipient.get(0).get("language"));

        List<Map<String, Object>> snapshot = jdbc.queryForList("""
                select form, normalized_form, content, family_id, band
                  from vocab_assignment_words where assignment_id = ? order by position
                """, id);
        int added = 0;
        int duplicated = 0;
        List<UUID> createdIds = new ArrayList<>();
        for (Map<String, Object> word : snapshot) {
            String form = String.valueOf(word.get("form"));
            String normalized = WordNormalizer.normalized(form);
            var existing = words.find(me.id(), language, normalized);
            UUID wordId;
            if (existing.isPresent()) {
                wordId = existing.get().id();
                if (existing.get().deleted()) {
                    words.restore(wordId);
                }
                duplicated++;
            } else {
                Object familyId = word.get("family_id");
                Object band = word.get("band");
                wordId = words.insert(me.id(), language, form, normalized,
                        familyId == null ? null : ((Number) familyId).longValue(),
                        band == null ? null : ((Number) band).intValue());
                Object content = word.get("content");
                if (content != null) {
                    words.setContent(wordId, String.valueOf(content), "READY");
                } else {
                    createdIds.add(wordId);
                }
                added++;
            }
            words.addAssignmentSource(wordId, id, teacherId);
        }
        jdbc.update("update vocab_assignment_recipients set claimed_at = ?"
                + " where assignment_id = ? and student_id = ?", Timestamp.from(clock.instant()), id, me.id());
        generation.enqueueNewWords(createdIds);
        return new ClaimResult(id, added, duplicated);
    }

    private void notifyStudents(UUID assignmentId, Set<UUID> recipients) {
        try {
            List<Ports.NotificationMessage> messages = recipients.stream()
                    .map(studentId -> new Ports.NotificationMessage(studentId, "VOCAB_ASSIGNMENT",
                            "VOCAB_ASSIGNMENT", assignmentId.toString(),
                            "新的单词预习", "老师发来一组生词，请在截止前预习。"))
                    .toList();
            notifications.notify(messages);
        } catch (RuntimeException ex) {
            log.warn("发词通知失败（不回滚发词）assignment={}：{}", assignmentId, ex.getMessage());
        }
    }

    private static void requireTeacher(CurrentUser me) {
        if (!me.is("TEACHER") && !me.is("ADMIN")) {
            throw ApiException.forbidden("teacher only");
        }
    }
}
