package com.teachloop.sitting.service;

import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.port.Ports;
import com.teachloop.sitting.domain.ObjectiveAnswer;
import com.teachloop.sitting.domain.ObjectiveAnswerRepository;
import com.teachloop.sitting.domain.Offering;
import com.teachloop.sitting.domain.OfferingRepository;
import com.teachloop.sitting.domain.Submission;
import com.teachloop.sitting.domain.SubmissionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * T102：改标准答案后，重算该试卷所有场次的快照、逐题对错与客观总分。
 * 只改自己模块的表；阅卷侧通过 submission 的客观分自然同步。
 */
@Component
public class ObjectiveRecomputeImpl implements Ports.ObjectiveRecompute {

    private final OfferingRepository offerings;
    private final SubmissionRepository submissions;
    private final ObjectiveAnswerRepository answers;
    private final SnapshotCodec codec;
    private final AuditPort audit;

    public ObjectiveRecomputeImpl(OfferingRepository offerings, SubmissionRepository submissions,
                                  ObjectiveAnswerRepository answers, SnapshotCodec codec, AuditPort audit) {
        this.offerings = offerings;
        this.submissions = submissions;
        this.answers = answers;
        this.codec = codec;
        this.audit = audit;
    }

    @Override
    @Transactional
    public int recomputePaper(UUID paperId, List<Ports.AnswerKey> keys) {
        Map<Integer, String> byNumber = keys.stream()
                .collect(Collectors.toMap(Ports.AnswerKey::number, Ports.AnswerKey::answerKey, (a, b) -> b));
        int recomputed = 0;
        for (Offering offering : offerings.findByPaperId(paperId)) {
            Ports.PaperSnapshot snapshot = codec.read(offering.getPaperSnapshot());
            List<Ports.ItemSpec> items = new ArrayList<>();
            boolean changed = false;
            for (Ports.ItemSpec item : snapshot.items()) {
                if (byNumber.containsKey(item.number())) {
                    items.add(item.withAnswerKey(byNumber.get(item.number())));
                    changed |= !java.util.Objects.equals(item.answerKey(), byNumber.get(item.number()));
                } else {
                    items.add(item);
                }
            }
            if (changed) {
                offering.setPaperSnapshot(codec.write(snapshot.withItems(items)));
                offerings.save(offering);
            }
            for (Submission submission : submissions.findByOfferingIdOrderByCreatedAtDesc(offering.getId())) {
                List<ObjectiveAnswer> rows = answers.findBySubmissionId(submission.getId());
                if (rows.isEmpty()) {
                    continue;
                }
                Map<Integer, Ports.ItemSpec> byItem = items.stream()
                        .collect(Collectors.toMap(Ports.ItemSpec::number, Function.identity(), (a, b) -> b));
                BigDecimal total = BigDecimal.ZERO;
                for (ObjectiveAnswer row : rows) {
                    Ports.ItemSpec item = byItem.get(row.getNumber());
                    if (item == null) {
                        continue;
                    }
                    BigDecimal award = ObjectiveScoring.award(item, row.getAnswer());
                    row.setCorrect(award.compareTo(item.points()) == 0);
                    total = total.add(award);
                }
                answers.saveAll(rows);
                submission.setObjectiveTotal(total);
                submissions.save(submission);
                recomputed++;
            }
            audit.record(new AuditEvent(null, "offering:objective_recomputed", "offering",
                    offering.getId().toString(), Map.of("paperId", paperId.toString())));
        }
        return recomputed;
    }
}
