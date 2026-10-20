package com.teachloop.grading.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiCallRepository extends JpaRepository<AiCall, UUID> {
}
