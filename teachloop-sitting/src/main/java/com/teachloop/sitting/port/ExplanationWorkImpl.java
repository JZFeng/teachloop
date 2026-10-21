package com.teachloop.sitting.port;

import com.teachloop.shared.port.Ports;
import com.teachloop.shared.text.BlankText;
import com.teachloop.sitting.domain.ObjectiveAnswer;
import com.teachloop.sitting.domain.ObjectiveAnswerRepository;
import com.teachloop.sitting.domain.Offering;
import com.teachloop.sitting.domain.OfferingRepository;
import com.teachloop.sitting.domain.Submission;
import com.teachloop.sitting.domain.SubmissionRepository;
import com.teachloop.sitting.service.SnapshotCodec;
import com.teachloop.shared.error.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 解析工作项（T087/T088）：按答错人次降序取前 N 道题。只传题目信息，不含学生身份（T086）。
 */
@Component
public class ExplanationWorkImpl implements Ports.ExplanationWorkQueue {

    private final OfferingRepository offerings;
    private final SubmissionRepository submissions;
    private final ObjectiveAnswerRepository answers;
    private final SnapshotCodec codec;

    public ExplanationWorkImpl(OfferingRepository offerings, SubmissionRepository submissions,
                               ObjectiveAnswerRepository answers, SnapshotCodec codec) {
        this.offerings = offerings;
        this.submissions = submissions;
        this.answers = answers;
        this.codec = codec;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Ports.ExplanationItem> wrongItemsOf(UUID offeringId, int limit) {
        Offering o = offerings.findById(offeringId)
                .orElseThrow(() -> ApiException.notFound("布置不存在"));
        Ports.PaperSnapshot snap = codec.read(o.getPaperSnapshot());
        Map<UUID, Ports.ItemSpec> byItem = new HashMap<>();
        snap.items().forEach(i -> byItem.put(i.itemId(), i));

        // 每个学生只看最新一次已提交且未被拒收的作答
        Map<UUID, Submission> latest = new LinkedHashMap<>();
        for (Submission s : submissions.findByOfferingIdOrderByCreatedAtDesc(offeringId)) {
            if (s.getSubmittedAt() == null || s.getRejectedAt() != null) {
                continue;
            }
            Submission current = latest.get(s.getStudentId());
            if (current == null || s.getAttemptNo() > current.getAttemptNo()) {
                latest.put(s.getStudentId(), s);
            }
        }

        Map<UUID, Integer> wrongCount = new HashMap<>();
        for (Submission s : latest.values()) {
            for (ObjectiveAnswer a : answers.findBySubmissionId(s.getId())) {
                Ports.ItemSpec spec = byItem.get(a.getItemId());
                if (spec == null || "ESSAY".equals(spec.questionType()) || a.isCorrect()) {
                    continue;
                }
                wrongCount.merge(a.getItemId(), 1, Integer::sum);
            }
        }

        List<Ports.ExplanationItem> out = new ArrayList<>();
        wrongCount.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(Math.max(0, Math.min(limit, 50)))
                .forEach(e -> {
                    Ports.ItemSpec spec = byItem.get(e.getKey());
                    out.add(new Ports.ExplanationItem(e.getKey(), spec.number(), spec.questionType(),
                            BlankText.forText(spec.stem()), spec.options(), spec.answerKey(), e.getValue(),
                            o.getFeedbackLanguage()));
                });
        return out;
    }
}
