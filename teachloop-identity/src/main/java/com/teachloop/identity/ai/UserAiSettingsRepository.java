package com.teachloop.identity.ai;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAiSettingsRepository extends JpaRepository<UserAiSettingsEntity, UUID> {
}
