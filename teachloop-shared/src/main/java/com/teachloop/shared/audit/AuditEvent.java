package com.teachloop.shared.audit;

import java.util.Map;
import java.util.UUID;

public record AuditEvent(UUID actorId, String action, String targetType, String targetId,
                         Map<String, Object> detail) {

    public static AuditEvent of(UUID actorId, String action, String targetType, String targetId) {
        return new AuditEvent(actorId, action, targetType, targetId, Map.of());
    }
}
