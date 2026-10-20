package com.teachloop.grading.service;

import com.teachloop.grading.domain.Review;
import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.shared.port.Ports;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** grading 暴露的只读复核投影：学生反馈只拿评语，不拿教师 id。 */
@Component
public class ReviewFeedbackImpl implements Ports.ReviewFeedback {

    private final ReviewRepository reviews;

    public ReviewFeedbackImpl(ReviewRepository reviews) {
        this.reviews = reviews;
    }

    @Override
    public Optional<Ports.ReviewNote> noteOf(java.util.UUID submissionId) {
        return reviews.findBySubmissionId(submissionId).map(ReviewFeedbackImpl::note);
    }

    private static Ports.ReviewNote note(Review review) {
        // T101：评论、优点、建议是三个独立字段；学生反馈分别展示，不暴露教师 id。
        String publishedAt = review.getPublishedAt() == null ? null : review.getPublishedAt().toString();
        return new Ports.ReviewNote(review.getStatus().name(), review.getComment(),
                review.getStrengths(), review.getSuggestions(), review.getCreatedAt().toString(), publishedAt);
    }
}
