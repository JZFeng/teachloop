package com.teachloop.vocab.assign;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.assign.VocabularyAssignmentRepository.AssignmentView;
import com.teachloop.vocab.assign.VocabularyAssignmentRepository.PendingView;
import com.teachloop.vocab.assign.VocabularyAssignmentRepository.PreviewWord;
import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.generation.VocabularyGenerationService;
import com.teachloop.vocab.word.VocabWordRepository;
import com.teachloop.vocab.word.WordNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * <p>分层：班级归属走 {@code Ports.ClassMembership}、词条写入走 {@link VocabWordRepository}，
 * SQL 在 {@link VocabularyAssignmentRepository}。
 */
@RestController
@RequestMapping("/api/vocab/assignments")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class VocabularyAssignmentEndpoint {

    private static final Logger log = LoggerFactory.getLogger(VocabularyAssignmentEndpoint.class);

    private final VocabularyAssignmentRepository assignments;
    private final Ports.ClassMembership classes;
    private final Ports.NotificationPort notifications;
    private final VocabWordRepository words;
    private final VocabularyGenerationService generation;
    private final ContentStore contentStore;
    private final Clock clock;

    public VocabularyAssignmentEndpoint(VocabularyAssignmentRepository assignments,
                                        Ports.ClassMembership classes,
                                        Ports.NotificationPort notifications,
                                        VocabWordRepository words,
                                        VocabularyGenerationService generation,
                                        ContentStore contentStore, Clock clock) {
        this.assignments = assignments;
        this.classes = classes;
        this.notifications = notifications;
        this.words = words;
        this.generation = generation;
        this.contentStore = contentStore;
        this.clock = clock;
    }

    public record CreateRequest(UUID setId, List<UUID> classIds, Instant dueAt, String note) {
    }

    public record Created(UUID id, int wordCount, int recipientCount) {
    }

    public record ClaimResult(UUID assignmentId, int added, int duplicated) {
    }

    public record StudentDetail(UUID id, String setName, Instant dueAt, String note, boolean expired,
                                boolean claimed, List<PreviewWord> words) {
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

        VocabularyAssignmentRepository.ActiveSet activeSet = assignments.activeSet(request.setId(), me.id())
                .orElseThrow(() -> ApiException.notFound("word set not found"));
        String language = activeSet.language();

        List<VocabularyAssignmentRepository.SetWordSnapshot> setWords =
                assignments.wordsOfSet(request.setId());
        if (setWords.isEmpty()) {
            throw ApiException.badRequest("word set is empty");
        }

        UUID assignmentId = UUID.randomUUID();
        Instant now = clock.instant();
        assignments.createAssignment(assignmentId, request.setId(), me.id(), request.dueAt(),
                request.note(), now);

        for (VocabularyAssignmentRepository.SetWordSnapshot word : setWords) {
            assignments.addAssignmentWord(UUID.randomUUID(), assignmentId, word, now);
        }

        // 收件人 = 选中的班级成员（快照）；同时记住每个学生落在这几个班的哪个班里，
        // 通知文案要写「三年二班的周杰伦老师」（2026-10-24 学生反馈）。
        Map<UUID, String> classNames = classes.classNames(classIds);
        Map<UUID, List<String>> classNamesByStudent = new LinkedHashMap<>();
        for (UUID classId : classIds) {
            assignments.linkClass(assignmentId, classId);
            String className = classNames.getOrDefault(classId, "");
            for (UUID studentId : classes.membersOf(classId)) {
                classNamesByStudent.computeIfAbsent(studentId, key -> new ArrayList<>()).add(className);
            }
        }
        for (UUID studentId : classNamesByStudent.keySet()) {
            assignments.addRecipient(assignmentId, studentId);
        }

        notifyStudents(assignmentId, activeSet.name(), me.displayName(), request.dueAt(),
                classNamesByStudent);
        assignments.touchSet(request.setId(), now);
        return new Created(assignmentId, setWords.size(), classNamesByStudent.size());
    }

    /** T409：教师发词记录，按时间倒序；领取情况是实发收件人与 claimed_at 的实时聚合。 */
    @GetMapping
    public List<AssignmentView> list(@RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        return assignments.listByTeacher(me.id());
    }

    @GetMapping("/pending")
    public List<PendingView> pending(@RequestAttribute("currentUser") CurrentUser me) {
        return assignments.pendingForStudent(me.id());
    }

    /** T413 补：学生预习页（逐词预览；过期仍可加入，界面标「已过期」）。 */
    @GetMapping("/{id}")
    public StudentDetail detail(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        VocabularyAssignmentRepository.RecipientView row = assignments.recipientOf(id, me.id())
                .orElseThrow(() -> notRecipient(id, me));
        Instant dueAt = row.dueAt();
        return new StudentDetail(id, row.setName(), dueAt, row.note(),
                dueAt != null && dueAt.isBefore(clock.instant()),
                row.claimed(), assignments.previewWords(id));
    }

    /**
     * 不是收件人时的口径：属主教师点开自己复制出去的学生链接，给 403 让他知道「这是学生链接」；
     * 其他人一律 404，不泄漏布置是否存在。
     */
    private ApiException notRecipient(UUID assignmentId, CurrentUser me) {
        boolean owner = assignments.teacherOf(assignmentId)
                .filter(teacherId -> teacherId.equals(me.id()))
                .isPresent();
        return owner
                ? ApiException.forbidden("teacher cannot preview a student link")
                : ApiException.notFound("assignment not found");
    }

    @PostMapping("/{id}/claim")
    @Transactional
    public ClaimResult claim(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        VocabularyAssignmentRepository.ClaimSource source = assignments.claimSource(id, me.id())
                .orElseThrow(() -> ApiException.notFound("assignment not found"));

        int added = 0;
        int duplicated = 0;
        List<UUID> createdIds = new ArrayList<>();
        for (VocabularyAssignmentRepository.ClaimWord word : assignments.assignmentWords(id)) {
            String form = word.form();
            String normalized = WordNormalizer.normalized(form);
            var existing = words.find(me.id(), source.language(), normalized);
            UUID wordId;
            if (existing.isPresent()) {
                wordId = existing.get().id();
                if (existing.get().deleted()) {
                    words.restore(wordId);
                }
                duplicated++;
            } else {
                wordId = words.insert(me.id(), source.language(), form, normalized,
                        word.familyId(), word.band());
                if (word.contentJson() != null) {
                    Long contentId = null;
                    try {
                        contentId = contentStore.put(source.language(), normalized,
                                word.contentJson(), "ASSIGNMENT");
                    } catch (RuntimeException ex) {
                        // T431：用户行不再存内容副本；delta 写失败时词条没有 content_id，
                        // 页面显示「未准备」，下次 prepare 会按词形重新查全局内容。
                        log.warn("发词快照写入全局 delta 失败 word={}（词条无 content_id，显示未准备）：{}",
                                form, ex.getMessage());
                    }
                    words.setContent(wordId, contentId, "READY");
                } else {
                    createdIds.add(wordId);
                }
                added++;
            }
            words.addAssignmentSource(wordId, id, source.teacherId());
        }
        assignments.markClaimed(id, me.id(), clock.instant());
        generation.enqueueNewWords(createdIds);
        return new ClaimResult(id, added, duplicated);
    }

    /**
     * A1：文案在读取时按学生界面语言渲染（模板见 messages*.properties）。
     *
     * <p>payload 只放事实：词集名、老师名、学生所在的班、截止时间（ISO-8601）。
     * 通知读取侧把它们填进「三年二班的周杰伦老师发来一组生词『…』，请在 … 之前预习」。
     */
    private void notifyStudents(UUID assignmentId, String setName, String teacherName, Instant dueAt,
                                Map<UUID, List<String>> classNamesByStudent) {
        try {
            List<Ports.NotificationMessage> messages = classNamesByStudent.entrySet().stream()
                    .map(entry -> new Ports.NotificationMessage(entry.getKey(), "VOCAB_ASSIGNMENT",
                            "VOCAB_ASSIGNMENT", assignmentId.toString(),
                            null, null, vocabPayload(setName, teacherName, entry.getValue(), dueAt)))
                    .toList();
            notifications.notify(messages);
        } catch (RuntimeException ex) {
            log.warn("发词通知失败（不回滚发词）assignment={}：{}", assignmentId, ex.getMessage());
        }
    }

    private static Map<String, String> vocabPayload(String setName, String teacherName,
                                                    List<String> classNames, Instant dueAt) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("setName", setName == null ? "" : setName);
        payload.put("teacher", teacherName == null ? "" : teacherName);
        payload.put("className", String.join("、", classNames));
        if (dueAt != null) {
            payload.put("dueAt", dueAt.toString());
        }
        return payload;
    }

    private static void requireTeacher(CurrentUser me) {
        if (!me.is("TEACHER") && !me.is("ADMIN")) {
            throw ApiException.forbidden("teacher only");
        }
    }
}
