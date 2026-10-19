package com.teachloop.grading.api;

import com.teachloop.grading.domain.Review;
import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.grading.domain.ReviewStatus;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 复核：老师对着 AI 草稿要么原样通过、要么改后通过、要么弃用重批。 */
@RestController
@RequestMapping("/api")
public class ReviewEndpoint {

    private final ReviewRepository reviews;
    private final AuditPort audit;
    private final Clock clock;

    public ReviewEndpoint(ReviewRepository reviews, AuditPort audit, Clock clock) {
        this.reviews = reviews;
        this.audit = audit;
        this.clock = clock;
    }

    public record Decision(@NotNull ReviewStatus status, String comment) {
    }

    public record ReviewView(String id, String submissionId, String status, String teacherId,
                             String comment, String createdAt) {
    }

    @GetMapping("/grading/queue")
    public List<ReviewView> queue() {
        return reviews.findByStatusOrderByCreatedAtAsc(ReviewStatus.PENDING).stream().map(ReviewEndpoint::view).toList();
    }

    @GetMapping("/submissions/{submissionId}/review")
    public ReviewView get(@PathVariable UUID submissionId) {
        return reviews.findBySubmissionId(submissionId).map(ReviewEndpoint::view)
                .orElseThrow(() -> ApiException.notFound("这份提交还没有复核记录"));
    }

    @PostMapping("/submissions/{submissionId}/review")
    @Transactional
    public ReviewView decide(@PathVariable UUID submissionId, @RequestBody @Valid Decision body,
                             @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能复核");
        }
        if (body.status() == ReviewStatus.PENDING) {
            throw ApiException.badRequest("复核结论只能是通过、改后通过或弃用重批");
        }
        Review r = reviews.findBySubmissionId(submissionId)
                .orElseGet(() -> new Review(UUID.randomUUID(), submissionId, me.id(),
                        ReviewStatus.PENDING, null, Instant.now(clock)));
        r.decide(body.status(), me.id(), body.comment());
        reviews.save(r);
        audit.record(new AuditEvent(me.id(), "review:" + body.status().name().toLowerCase(),
                "submission", submissionId.toString(), Map.of("hasComment", body.comment() != null)));
        return view(r);
    }

    private static ReviewView view(Review r) {
        return new ReviewView(r.getId().toString(), r.getSubmissionId().toString(), r.getStatus().name(),
                r.getTeacherId() == null ? null : r.getTeacherId().toString(),
                r.getComment(), r.getCreatedAt().toString());
    }
}
