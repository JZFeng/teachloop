package com.teachloop.grading.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, UUID> {
    Optional<Review> findBySubmissionId(UUID submissionId);

    List<Review> findByStatusOrderByCreatedAtAsc(ReviewStatus status);
}
