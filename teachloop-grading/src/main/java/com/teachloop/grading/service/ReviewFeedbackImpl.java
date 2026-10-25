package com.teachloop.grading.service;

import com.teachloop.grading.domain.Review;
import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.grading.domain.ReviewText;
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
        // 学生页自己渲染「优点：」「建议：」标签，正文里重复的字段名前缀在这里剥掉（含旧数据）。
        String publishedAt = review.getPublishedAt() == null ? null : review.getPublishedAt().toString();
        return new Ports.ReviewNote(review.getStatus().name(), review.getComment(),
                ReviewText.stripLabel(review.getStrengths()), ReviewText.stripLabel(review.getSuggestions()),
                review.getCreatedAt().toString(), publishedAt);
    }
}
