package com.teachloop.sitting.service;

import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.sitting.api.SittingDtos;
import com.teachloop.sitting.domain.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 考试计时的唯一事实来源（AC-26.7 / AC-26.8 / T199）。
 *
 * <p>剩余时间永远由服务端算：<code>deadline_at − now</code>，并把 <code>started_at</code> /
 * <code>deadline_at</code> 落库，时长之后被改也不影响进行中的考试。
 */
@Service
public class ExamSessionService {

    private final OfferingRepository offerings;
    private final SubmissionRepository submissions;
    private final ObjectiveAnswerRepository answers;
    private final SnapshotCodec codec;
    private final OfferingWindows windows;
    private final AuditPort audit;
    private final Clock clock;

    public ExamSessionService(OfferingRepository offerings, SubmissionRepository submissions,
                              ObjectiveAnswerRepository answers, SnapshotCodec codec,
                              OfferingWindows windows, AuditPort audit, Clock clock) {
        this.offerings = offerings;
        this.submissions = submissions;
        this.answers = answers;
        this.codec = codec;
        this.windows = windows;
        this.audit = audit;
        this.clock = clock;
    }

    /** 作答会话（fixed_window 下可能还没有数据库行，计时已经在跑）。 */
    public record SessionView(Submission submission, boolean started, Instant startedAt, Instant deadlineAt,
                              long remainingSeconds, boolean submitted, boolean autoSubmitted, boolean locked) {
    }

    /** 自动交卷 / 提交后的落库结果。 */
    public record Finalized(Submission submission, BigDecimal objectiveScore, int totalPoints,
                            boolean autoSubmitted, long lateBySeconds) {
    }

    public SessionView session(Offering offering, UUID studentId, boolean requireParticipant) {
        if (requireParticipant && !windows.isParticipant(offering, studentId)) {
            // 不泄漏存在性：不是参与班的学生当作看不到这场考试
            throw ApiException.notFound("考试不存在");
        }
        OfferingWindows.Window window = windows.forStudent(offering, studentId);
        Submission active = submissions.findByOfferingIdAndStudentIdAndActiveTrue(offering.getId(), studentId)
                .orElse(null);
        Instant now = Instant.now(clock);
        Instant startedAt = null;
        Instant deadlineAt = null;

        if (active != null && active.getStartedAt() != null) {
            startedAt = active.getStartedAt();
            deadlineAt = active.getDeadlineAt() != null ? active.getDeadlineAt()
                    : startedAt.plus(Duration.ofMinutes(window.durationMinutes()));
        } else if (offering.getKind() == OfferingKind.EXAM
                && offering.getTimingMode() == TimingMode.FIXED_WINDOW
                && offering.getStartAt() != null
                && !now.isBefore(offering.getStartAt())) {
            // 统一开考：没点开始的学生也在计时（AC-26.5）
            startedAt = offering.getStartAt();
            deadlineAt = startedAt.plus(Duration.ofMinutes(window.durationMinutes()));
        }

        boolean started = startedAt != null;
        boolean submitted = active != null && active.getSubmittedAt() != null;
        long remaining = deadlineAt == null
                ? Duration.ofMinutes(window.durationMinutes()).toSeconds()
                : Math.max(0, Duration.between(now, deadlineAt).toSeconds());
        boolean locked = submitted || (deadlineAt != null && remaining <= 0);
        return new SessionView(active, started, startedAt, deadlineAt, remaining, submitted,
                active != null && active.isAutoSubmitted(), locked);
    }

    /** 学生点「开始答题」；同一场同一生重复点击幂等，已交卷则不再新建。 */
    @Transactional
    public SessionView start(Offering offering, UUID studentId) {
        if (!windows.isParticipant(offering, studentId)) {
            throw ApiException.notFound("考试不存在");
        }
        Instant now = Instant.now(clock);
        OfferingWindows.Window window = windows.forStudent(offering, studentId);
        Submission active = submissions.findByOfferingIdAndStudentIdAndActiveTrue(offering.getId(), studentId)
                .orElse(null);

        if (active != null && active.getSubmittedAt() != null) {
            // 已交卷：不再起算，前端显示交卷状态
            return session(offering, studentId, false);
        }
        if (active != null) {
            if (active.getDeadlineAt() != null && now.isAfter(active.getDeadlineAt())) {
                autoSubmit(active);
            }
            return session(offering, studentId, false);
        }

        if (offering.getKind() == OfferingKind.ASSIGNMENT) {
            createAttempt(offering, studentId, now, null, null);
        } else if (offering.getTimingMode() == TimingMode.FIXED_WINDOW) {
            if (offering.getStartAt() == null) {
                throw ApiException.conflict("本场未设置统一开考时间，无法开始");
            }
            if (now.isBefore(offering.getStartAt())) {
                throw ApiException.conflict("未到开考时间");
            }
            Instant deadlineAt = offering.getStartAt().plus(Duration.ofMinutes(window.durationMinutes()));
            if (now.isAfter(deadlineAt)) {
                throw ApiException.conflict("本场考试已结束");
            }
            createAttempt(offering, studentId, now, offering.getStartAt(), deadlineAt);
        } else {
            if (now.isAfter(window.dueAt())) {
                throw ApiException.conflict("考试已结束，不能再开始");
            }
            createAttempt(offering, studentId, now, now, now.plus(Duration.ofMinutes(window.durationMinutes())));
        }
        Submission created = submissions.findByOfferingIdAndStudentIdAndActiveTrue(offering.getId(), studentId)
                .orElseThrow(() -> ApiException.conflict("无法开始作答"));
        audit.record(new AuditEvent(studentId, "exam:start", "submission", created.getId().toString(),
                Map.of("offeringId", offering.getId().toString(), "timingMode", offering.getTimingMode().name())));
        return session(offering, studentId, false);
    }

    /**
     * 自动保存：只写草稿，不交卷；到点后由服务端定稿。
     * 到点定稿必须落库后再返回 409，所以 ApiException 不回滚事务。
     */
    @Transactional(noRollbackFor = ApiException.class)
    public SessionView saveDraft(Offering offering, UUID studentId, String contentText,
                                 List<SittingDtos.AnswerInput> answerInputs) {
        Submission s = requireDraft(offering, studentId);
        Instant now = Instant.now(clock);
        writeAnswers(offering, s, contentText, answerInputs, now);
        submissions.save(s);
        return session(offering, studentId, false);
    }

    /** 手动交卷：以本次请求携带的作答为准；到点定稿同样要先落库再报冲突。 */
    @Transactional(noRollbackFor = ApiException.class)
    public Finalized submit(Offering offering, UUID studentId, String contentText,
                            List<SittingDtos.AnswerInput> answerInputs) {
        Submission s = submissions.findByOfferingIdAndStudentIdAndActiveTrue(offering.getId(), studentId)
                .orElseGet(() -> {
                    // 兼容「没有单独点开始就直接交卷」：按开始规则起算
                    start(offering, studentId);
                    return submissions.findByOfferingIdAndStudentIdAndActiveTrue(offering.getId(), studentId)
                            .orElseThrow(() -> ApiException.conflict("无法开始作答"));
                });
        if (s.getSubmittedAt() != null) {
            throw ApiException.conflict("已交卷，不能重复提交");
        }
        Instant now = Instant.now(clock);
        if (s.getDeadlineAt() != null && now.isAfter(s.getDeadlineAt())) {
            autoSubmit(s);
            throw ApiException.conflict("已到交卷时间，试卷已自动提交");
        }
        writeAnswers(offering, s, contentText, answerInputs, now);
        submissions.save(s);
        Finalized finalized = finalizeSubmission(offering, s, now, false);
        audit.record(new AuditEvent(studentId, "submission:submit", "submission", s.getId().toString(),
                Map.of("attempt", s.getAttemptNo(), "late", finalized.lateBySeconds())));
        return finalized;
    }

    /** 到点定稿：以最后一次自动保存为准，条件更新保证幂等（AC-26.8）。 */
    @Transactional
    public Finalized autoSubmit(Submission s) {
        return finalizeSubmission(offeringOf(s), s, Instant.now(clock), true);
    }

    /** 扫过期草稿；返回本次定稿份数。 */
    @Transactional
    public int autoSubmitExpired() {
        Instant now = Instant.now(clock);
        List<UUID> expired = submissions.findExpiredDraftIds(now);
        int done = 0;
        for (UUID id : expired) {
            Submission s = submissions.findById(id).orElse(null);
            if (s == null) {
                continue;
            }
            Offering offering = offeringOf(s);
            Finalized f = finalizeSubmission(offering, s, now, true);
            if (f.autoSubmitted()) {
                audit.record(new AuditEvent(null, "submission:auto_submit", "submission", s.getId().toString(),
                        Map.of("deadlineAt", String.valueOf(s.getDeadlineAt()))));
                done++;
            }
        }
        return done;
    }

    private Finalized finalizeSubmission(Offering offering, Submission s, Instant now, boolean auto) {
        if (s.getSubmittedAt() != null) {
            return new Finalized(s, s.getObjectiveTotal(), totalPoints(offering), s.isAutoSubmitted(),
                    s.getLateBySeconds());
        }
        int total = totalPoints(offering);
        String state = gradingStateFor(offering, s);
        if (auto) {
            int updated = submissions.markAutoSubmitted(s.getId(), now, state);
            s = submissions.findById(s.getId()).orElseThrow();
            if (updated == 0) {
                return new Finalized(s, s.getObjectiveTotal(), total, s.isAutoSubmitted(), s.getLateBySeconds());
            }
        } else {
            s.setSubmittedAt(now);
            s.setAutoSubmitted(false);
            s.setLateBySeconds(offering.getKind() == OfferingKind.ASSIGNMENT
                    ? Math.max(0, Duration.between(
                            windows.forStudent(offering, s.getStudentId()).dueAt(), now).toSeconds())
                    : 0);
            s.setGradingState(GradingState.valueOf(state));
            submissions.save(s);
        }
        return new Finalized(s, s.getObjectiveTotal(), total, s.isAutoSubmitted(), s.getLateBySeconds());
    }

    // ---------- internals ----------

    private Submission requireDraft(Offering offering, UUID studentId) {
        Submission s = submissions.findByOfferingIdAndStudentIdAndActiveTrue(offering.getId(), studentId)
                .orElseThrow(() -> ApiException.conflict("请先点击开始答题"));
        if (s.getSubmittedAt() != null) {
            throw ApiException.conflict("已交卷，不能继续作答");
        }
        if (s.getDeadlineAt() != null && Instant.now(clock).isAfter(s.getDeadlineAt())) {
            autoSubmit(s);
            throw ApiException.conflict("时间到，试卷已自动交卷");
        }
        return s;
    }

    private Submission createAttempt(Offering offering, UUID studentId, Instant now,
                                     Instant startedAt, Instant deadlineAt) {
        List<Submission> attempts = submissions.findByOfferingIdAndStudentIdOrderByAttemptNoDesc(
                offering.getId(), studentId);
        if (attempts.size() >= offering.getMaxAttempts()) {
            throw ApiException.conflict("已达到最大尝试次数");
        }
        for (Submission old : attempts) {
            if (old.isActive()) {
                old.setActive(false);
            }
        }
        int attemptNo = attempts.isEmpty() ? 1 : attempts.get(0).getAttemptNo() + 1;
        Submission s = new Submission(UUID.randomUUID(), offering.getId(), studentId, attemptNo, "TEXT",
                null, GradingState.DRAFT, now);
        if (offering.getKind() == OfferingKind.EXAM) {
            s.setStartedAt(startedAt);
            s.setDeadlineAt(deadlineAt);
        }
        return submissions.save(s);
    }

    private void writeAnswers(Offering offering, Submission s, String contentText,
                              List<SittingDtos.AnswerInput> inputs, Instant now) {
        Ports.PaperSnapshot snap = codec.read(offering.getPaperSnapshot());
        s.setContentText(contentText);
        answers.deleteBySubmissionId(s.getId());

        Map<Integer, Ports.ItemSpec> byNumber = new HashMap<>();
        snap.items().forEach(i -> byNumber.put(i.number(), i));
        BigDecimal objective = BigDecimal.ZERO;
        if (inputs != null) {
            for (SittingDtos.AnswerInput a : inputs) {
                Ports.ItemSpec spec = byNumber.get(a.number());
                if (spec == null) {
                    continue;
                }
                String given = a.answer() == null ? "" : a.answer().trim();
                var row = new ObjectiveAnswer(UUID.randomUUID(), s.getId(), spec.itemId(), a.number(), given);
                java.math.BigDecimal awarded = ObjectiveScoring.award(spec, given);
                row.setCorrect(awarded.compareTo(spec.points()) == 0
                        && !"ESSAY".equals(spec.questionType()));
                answers.save(row);
                objective = objective.add(awarded);
            }
        }
        s.setObjectiveTotal(objective);
        if (offering.getKind() == OfferingKind.ASSIGNMENT) {
            s.setLateBySeconds(Math.max(0,
                    Duration.between(windows.forStudent(offering, s.getStudentId()).dueAt(), now).toSeconds()));
        }
    }

    private Offering offeringOf(Submission s) {
        return offerings.findById(s.getOfferingId()).orElseThrow(() -> ApiException.notFound("布置不存在"));
    }

    /**
     * 交卷后的初始工作项状态（T092）：有主观作答或正文 → QUEUED，等这一场被派发后再转 PENDING；
     * 全客观题 → 直接 DONE（客观分在提交时已判）。
     *
     * <p>QUEUED 而不是 PENDING 是「一场一条 run」的前提：worker 只领 PENDING，
     * 未派发的提交不会被提前批改，迟到提交也还能进同一个 run。
     */
    private String gradingStateFor(Offering offering, Submission s) {
        Ports.PaperSnapshot snap = codec.read(offering.getPaperSnapshot());
        boolean hasEssayAnswer = answers.findBySubmissionId(s.getId()).stream()
                .anyMatch(a -> snap.items().stream()
                        .anyMatch(i -> i.number() == a.getNumber() && "ESSAY".equals(i.questionType())));
        boolean hasText = s.getContentText() != null && !s.getContentText().isBlank();
        return hasEssayAnswer || hasText ? GradingState.QUEUED.name() : GradingState.DONE.name();
    }

    private int totalPoints(Offering offering) {
        return codec.read(offering.getPaperSnapshot()).totalPoints();
    }


}
