package com.teachloop.sitting.port;

import com.teachloop.sitting.domain.*;
import com.teachloop.sitting.service.SnapshotCodec;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** sitting 实现的三个端口：读作答（SittingLedger）、写分数（GradingOutcome）、推进工作项（GradingWorkQueue）。 */
@Component
public class SittingLedgerImpl implements Ports.SittingLedger, Ports.GradingOutcome, Ports.GradingWorkQueue {

    private final SubmissionRepository submissions;
    private final ObjectiveAnswerRepository answers;
    private final OfferingRepository offerings;
    private final SnapshotCodec codec;
    private final Clock clock;

    public SittingLedgerImpl(SubmissionRepository submissions, ObjectiveAnswerRepository answers,
                             OfferingRepository offerings, SnapshotCodec codec, Clock clock) {
        this.submissions = submissions;
        this.answers = answers;
        this.offerings = offerings;
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Ports.WorkItem load(UUID submissionId) {
        Submission s = submissions.findById(submissionId).orElseThrow(() -> ApiException.notFound("提交不存在"));
        Offering o = offerings.findById(s.getOfferingId()).orElseThrow(() -> ApiException.notFound("布置不存在"));
        Ports.PaperSnapshot snap = codec.read(o.getPaperSnapshot());
        List<Ports.AnswerRow> rows = answers.findBySubmissionId(submissionId).stream()
                .map(a -> new Ports.AnswerRow(a.getItemId(), a.getNumber(), a.getAnswer()))
                .toList();
        return new Ports.WorkItem(s.getId(), o.getId(), s.getStudentId(), "",
                s.getInputKind(), s.getContentText(), rows, snap.items(),
                o.getRubricMarkdown(), o.getGradingPromptTemplate(), o.getFeedbackLanguage());
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
            s.setObjectiveTotal(result.objectivePoints());
        }
        s.setSubjectiveTotal(nz(result.subjectivePoints()));
        s.setGradingState(GradingState.DONE);
        s.setGradingError(null);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
