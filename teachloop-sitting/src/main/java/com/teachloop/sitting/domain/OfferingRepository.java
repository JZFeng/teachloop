package com.teachloop.sitting.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfferingRepository extends JpaRepository<Offering, UUID> {
    List<Offering> findByStatusOrderByDueAtDesc(OfferingStatus status);
}
