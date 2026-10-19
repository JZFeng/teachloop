package com.teachloop.grading.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GradingRunRepository extends JpaRepository<GradingRun, UUID> {
}
