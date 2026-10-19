package com.teachloop.sitting.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfferingClassRepository extends JpaRepository<OfferingClass, UUID> {
    List<OfferingClass> findByOfferingId(UUID offeringId);

    List<OfferingClass> findByClassId(UUID classId);
}
