package com.teachloop.identity.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiProviderRepository extends JpaRepository<AiProviderEntity, UUID> {

    Optional<AiProviderEntity> findByProviderKey(String providerKey);

    List<AiProviderEntity> findAllByOrderByDisplayNameAsc();
}
