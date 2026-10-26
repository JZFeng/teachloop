package com.teachloop.identity.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiProviderRepository extends JpaRepository<AiProviderEntity, UUID> {

    Optional<AiProviderEntity> findByProviderKey(String providerKey);

    List<AiProviderEntity> findAllByOrderByDisplayNameAsc();

    /** 决策 022 D1：故障转移顺序（priority 小者优先，同值按创建时间）。 */
    List<AiProviderEntity> findAllByOrderByPriorityAscCreatedAtAsc();
}
