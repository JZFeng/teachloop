package com.teachloop.sitting.api;

import com.teachloop.shared.security.CurrentUser;
import com.teachloop.sitting.domain.*;
import com.teachloop.sitting.service.SnapshotCodec;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/offerings/{offeringId}")
public class SubmissionEndpoint {

    private final OfferingRepository offerings;
    private final SubmissionRepository submissions;
    private final ObjectiveAnswerRepository answers;
    private final SnapshotCodec codec;
    private final AuditPort audit;
    private final Clock clock;

    public SubmissionEndpoint(OfferingRepository offerings, SubmissionRepository submissions,
                              ObjectiveAnswerRepository answers, SnapshotCodec codec,
                              AuditPort audit, Clock clock) {
        this.offerings = offerings;
        this.submissions = submissions;
        this.answers = answers;
        this.codec = codec;
        this.audit = audit;
        this.clock = clock;
    }

    /** 学生点「开始答题」：起算计时（考试型），返回剩余秒数。 */
    @PostMapping("/start")
    @Transactional
    public Map<String, Object> start(@PathVariable UUID offeringId, @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        Submission s = submissions.findByOfferingIdAndStudentIdAndActiveTrue(offeringId, me.id())
                .orElseGet(() -> {
                    GradingState state = GradingState.DONE;
                    var created = new Submission(UUID.randomUUID(), offeringId, me.id(), 1, "TEXT", null,
                            state, Instant.now(clock));
                    if (o.getKind() == OfferingKind.EXAM) {
                        Instant started = Instant.now(clock);
                        created.setStartedAt(started);
                        created.setDeadlineAt(started.plus(Duration.ofMinutes(o.getDurationMinutes())));
                    }
                    return submissions.save(created);
                });
        long remaining = s.getDeadlineAt() == null ? -1
                : Math.max(0, Duration.between(Instant.now(clock), s.getDeadlineAt()).toSeconds());
        return Map.of("submissionId", s.getId().toString(), "remainingSeconds", remaining);
    }

    @PostMapping("/submissions")
    @Transactional
    public SittingDtos.SubmitResponse submit(@PathVariable UUID offeringId,
                                             @RequestBody SittingDtos.SubmitRequest body,
                                             @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        Ports.PaperSnapshot snap = codec.read(o.getPaperSnapshot());
        Instant now = Instant.now(clock);

        Submission s = submissions.findByOfferingIdAndStudentIdAndActiveTrue(offeringId, me.id())
                .orElseGet(() -> {
                    int attempt = (int) submissions.findByOfferingIdOrderByCreatedAtDesc(offeringId).stream()
                            .filter(x -> x.getStudentId().equals(me.id())).count() + 1;
                    if (attempt > o.getMaxAttempts()) {
                        throw ApiException.conflict("已达到最大尝试次数");
                    }
                    return submissions.save(new Submission(UUID.randomUUID(), offeringId, me.id(), attempt,
                            "TEXT", null, GradingState.PENDING, now));
                });

        if (s.getDeadlineAt() != null && now.isAfter(s.getDeadlineAt())) {
            s.setAutoSubmitted(true);
            throw ApiException.conflict("已到交卷时间，试卷已自动提交");
        }
        s.setContentText(body.contentText());
        s.setLateBySeconds(Math.max(0, Duration.between(o.getDueAt(), now).toSeconds()));

        Map<Integer, Ports.ItemSpec> byNumber = new HashMap<>();
        snap.items().forEach(i -> byNumber.put(i.number(), i));
        BigDecimal objective = BigDecimal.ZERO;
        boolean hasEssay = false;
        if (body.answers() != null) {
            for (SittingDtos.AnswerInput a : body.answers()) {
                Ports.ItemSpec spec = byNumber.get(a.number());
                if (spec == null) {
                    continue;
                }
                String given = a.answer() == null ? "" : a.answer().trim();
                answers.save(new ObjectiveAnswer(UUID.randomUUID(), s.getId(), spec.itemId(), a.number(), given));
                if ("ESSAY".equals(spec.questionType())) {
                    hasEssay = true;
                } else if (correct(spec, given)) {
                    objective = objective.add(spec.points());
                }
            }
        }
        s.setObjectiveTotal(objective);
        s.setGradingState(hasEssay || (body.contentText() != null && !body.contentText().isBlank())
                ? GradingState.PENDING : GradingState.DONE);
        audit.record(new AuditEvent(me.id(), "submission:submit", "submission", s.getId().toString(),
                java.util.Map.of("attempt", s.getAttemptNo(), "late", s.getLateBySeconds() > 0)));
        return new SittingDtos.SubmitResponse(s.getId().toString(), s.getAttemptNo(), objective,
                BigDecimal.valueOf(snap.totalPoints()), s.getGradingState().name());
    }

    private static boolean correct(Ports.ItemSpec spec, String given) {
        if (spec.answerKey() == null) {
            return false;
        }
        String key = spec.answerKey().trim();
        if ("MULTIPLE_CHOICE".equals(spec.questionType())) {
            Set<String> a = tokens(given);
            Set<String> b = tokens(key);
            return !a.isEmpty() && a.equals(b);
        }
        return key.equalsIgnoreCase(given);
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new TreeSet<>();
        for (String t : s.toUpperCase().split("[^A-Z]")) {
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    private Offering published(UUID id) {
        Offering o = offerings.findById(id).orElseThrow(() -> ApiException.notFound("布置不存在"));
        if (o.getStatus() != OfferingStatus.PUBLISHED) {
            throw ApiException.notFound("布置不存在");
        }
        return o;
    }
}
