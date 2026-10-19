package com.teachloop.sitting.api;

import com.teachloop.shared.security.CurrentUser;
import com.teachloop.sitting.domain.*;
import com.teachloop.sitting.service.SnapshotCodec;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/offerings")
public class OfferingEndpoint {

    private final OfferingRepository offerings;
    private final OfferingClassRepository offeringClasses;
    private final SubmissionRepository submissions;
    private final Ports.PaperCatalog paperCatalog;
    private final SnapshotCodec codec;
    private final AuditPort audit;
    private final Clock clock;

    public OfferingEndpoint(OfferingRepository offerings, OfferingClassRepository offeringClasses,
                            SubmissionRepository submissions, Ports.PaperCatalog paperCatalog,
                            SnapshotCodec codec, AuditPort audit, Clock clock) {
        this.offerings = offerings;
        this.offeringClasses = offeringClasses;
        this.submissions = submissions;
        this.paperCatalog = paperCatalog;
        this.codec = codec;
        this.audit = audit;
        this.clock = clock;
    }

    /** 布置：一次挂多个班。试卷本身不含班级。 */
    @PostMapping
    @Transactional
    public SittingDtos.OfferingView create(@RequestBody SittingDtos.CreateOffering body,
                                           @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能布置考试");
        }
        if (body.classIds() == null || body.classIds().isEmpty()) {
            throw ApiException.badRequest("至少要选一个班级");
        }
        UUID paperId = UUID.fromString(body.paperId());
        Ports.PaperSnapshot snap = paperCatalog.load(paperId);
        OfferingKind kind = body.kind() == null ? OfferingKind.ASSIGNMENT : OfferingKind.valueOf(body.kind());
        Instant dueAt = body.dueAt() == null ? Instant.now(clock).plusSeconds(7 * 86400) : Instant.parse(body.dueAt());
        Integer duration = body.durationMinutes() != null ? body.durationMinutes() : snap.durationMinutes();
        var o = new Offering(UUID.randomUUID(), paperId, kind,
                body.startAt() == null ? null : Instant.parse(body.startAt()),
                dueAt, duration,
                body.timingMode() == null ? TimingMode.INDIVIDUAL : TimingMode.valueOf(body.timingMode()),
                codec.write(snap), 1, snap.rubricMarkdown(), null, Instant.now(clock));
        offerings.save(o);
        for (String cid : body.classIds()) {
            offeringClasses.save(new OfferingClass(UUID.randomUUID(), o.getId(), UUID.fromString(cid)));
        }
        audit.record(new AuditEvent(me.id(), "offering:create", "offering", o.getId().toString(),
                java.util.Map.of("classes", body.classIds().size(), "kind", kind.name())));
        return view(o);
    }

    @GetMapping
    public List<SittingDtos.OfferingView> list() {
        return offerings.findByStatusOrderByDueAtDesc(OfferingStatus.PUBLISHED).stream().map(this::view).toList();
    }

    @PostMapping("/{id}/publish")
    @Transactional
    public SittingDtos.OfferingView publish(@PathVariable UUID id,
                                            @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = offerings.findById(id).orElseThrow(() -> ApiException.notFound("布置不存在"));
        o.setStatus(OfferingStatus.PUBLISHED);
        o.setPublishScores(true);
        audit.record(AuditEvent.of(me.id(), "offering:publish", "offering", id.toString()));
        return view(o);
    }

    @GetMapping("/{id}/progress")
    public SittingDtos.ProgressView progress(@PathVariable UUID id) {
        long done = submissions.countByOfferingIdAndGradingState(id, GradingState.DONE);
        long running = submissions.countByOfferingIdAndGradingState(id, GradingState.RUNNING);
        long pending = submissions.countByOfferingIdAndGradingState(id, GradingState.PENDING);
        long failed = submissions.countByOfferingIdAndGradingState(id, GradingState.FAILED);
        int total = submissions.findByOfferingIdOrderByCreatedAtDesc(id).size();
        return new SittingDtos.ProgressView(total, done, running, pending, failed);
    }

    private SittingDtos.OfferingView view(Offering o) {
        return new SittingDtos.OfferingView(o.getId().toString(), o.getPaperId().toString(), o.getKind().name(),
                o.getDueAt().toString(), o.getDurationMinutes(), o.getTimingMode().name(), o.getStatus().name(),
                offeringClasses.findByOfferingId(o.getId()).size(),
                submissions.findByOfferingIdOrderByCreatedAtDesc(o.getId()).size(),
                o.isPublishScores());
    }
}
