package com.teachloop.sitting.port;

import com.teachloop.sitting.domain.*;
import com.teachloop.sitting.service.ObjectiveScoring;
import com.teachloop.sitting.service.SnapshotCodec;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** sitting 实现的三个端口：读作答与分数（SittingLedger）、写分数（GradingOutcome）、推进工作项（GradingWorkQueue）。 */
@Component
public class SittingLedgerImpl implements Ports.SittingLedger, Ports.GradingOutcome, Ports.GradingWorkQueue {

    private final SubmissionRepository submissions;
    private final ObjectiveAnswerRepository answers;
    private final OfferingRepository offerings;
    private final SnapshotCodec codec;
    private final Ports.StudentDirectory directory;
    private final Clock clock;

    public SittingLedgerImpl(SubmissionRepository submissions, ObjectiveAnswerRepository answers,
                             OfferingRepository offerings, SnapshotCodec codec,
                             Ports.StudentDirectory directory, Clock clock) {
        this.submissions = submissions;
        this.answers = answers;
        this.offerings = offerings;
        this.codec = codec;
        this.directory = directory;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Ports.WorkItem load(UUID submissionId) {
        Submission s = submissions.findById(submissionId).orElseThrow(() -> ApiException.notFound("提交不存在"));
        Offering o = offerings.findById(s.getOfferingId()).orElseThrow(() -> ApiException.notFound("布置不存在"));
        Ports.PaperSnapshot snap = codec.read(o.getPaperSnapshot());
        // 复核页要逐题显示「答对/答错/实得分」：对错取提交时落库的判定，
        // 实得分按快照标准答案与同一套 ObjectiveScoring 重算，保证与交卷时的客观分一致。
        Map<Integer, Ports.ItemSpec> specsByNumber = new HashMap<>();
        snap.items().forEach(i -> specsByNumber.put(i.number(), i));
        List<Ports.AnswerRow> rows = answers.findBySubmissionId(submissionId).stream()
                .map(a -> {
                    Ports.ItemSpec spec = specsByNumber.get(a.getNumber());
                    boolean autoGraded = spec != null
                            && !"ESSAY".equals(spec.questionType())
                            && spec.answerKey() != null;
                    BigDecimal awarded = autoGraded
                            ? ObjectiveScoring.award(spec, a.getAnswer())
                            : null;
                    return new Ports.AnswerRow(a.getItemId(), a.getNumber(), a.getAnswer(),
                            a.isCorrect(), awarded);
                })
                .toList();
        String language = directory.locales(List.of(s.getStudentId()))
                .getOrDefault(s.getStudentId(), o.getFeedbackLanguage());
        if (language == null || language.isBlank()) {
            language = o.getFeedbackLanguage();
        }
        // D-29：试卷级 rubric 退役。考试只认题项级 rubric（没有就按默认 80/20 合成），
        // 即使旧行的 offerings.rubric_markdown 还留着内容也不再使用；独立作业仍用自己的 rubric。
        String paperLevelRubric = o.getKind() == OfferingKind.EXAM ? null : o.getRubricMarkdown();
        return new Ports.WorkItem(s.getId(), o.getId(), s.getStudentId(), "",
                s.getInputKind(), s.getContentText(), s.getContentHtml(), rows, snap.items(),
                paperLevelRubric, o.getGradingPromptTemplate(), language, o.getCreatedBy(), o.getAnchorExamples());
    }

    @Override
    @Transactional(readOnly = true)
    public Ports.SubmissionScores scores(UUID submissionId) {
        Submission s = submissions.findById(submissionId).orElseThrow(() -> ApiException.notFound("提交不存在"));
        return new Ports.SubmissionScores(s.getId(), s.getSubmittedAt(), s.getObjectiveTotal(),
                s.getSubjectiveTotal(), s.getGradingState().name());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> pendingIds(int limit) {
        return submissions.findPendingIds().stream().limit(limit).toList();
    }

    @Override
    @Transactional
    public void markRunning(UUID submissionId) {
        submissions.claim(submissionId, Instant.now(clock));
    }

    @Override
    @Transactional
    public void markDone(UUID submissionId, Ports.GradingResult result) {
        apply(submissionId, result);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenWork(UUID offeringId) {
        return submissions.countOpenWork(offeringId) > 0;
    }

    @Override
    @Transactional
    public void markFailed(UUID submissionId, String error) {
        Submission s = submissions.findById(submissionId).orElseThrow();
        s.setGradingState(GradingState.FAILED);
        s.setGradingError(error);
    }

    @Override
    @Transactional
    public void apply(UUID submissionId, Ports.GradingResult result) {
        Submission s = submissions.findById(submissionId).orElseThrow();
        // 客观分在提交时由代码判定，模型不得覆盖；只有显式给出时才采用
        if (result.objectivePoints() != null) {
            s.setObjectiveTotal(result.objectivePoints()); // T071：负分/空值在 setter 统一拒绝
        }
        s.setSubjectiveTotal(nz(result.subjectivePoints()));
        s.setGradingState(GradingState.DONE);
        s.setGradingError(null);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
