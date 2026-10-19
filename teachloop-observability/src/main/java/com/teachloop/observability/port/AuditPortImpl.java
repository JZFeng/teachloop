package com.teachloop.observability.port;

import com.teachloop.observability.domain.AuditLog;
import com.teachloop.observability.domain.AuditLogRepository;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AuditPortImpl implements AuditPort {

    private final AuditLogRepository logs;
    private final Clock clock;

    public AuditPortImpl(AuditLogRepository logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    @Override
    public void record(AuditEvent event) {
        String detail = event.detail() == null || event.detail().isEmpty() ? null : event.detail().toString();
        logs.save(new AuditLog(UUID.randomUUID(), event.actorId(), event.action(), event.targetType(),
                event.targetId(), detail, Instant.now(clock)));
    }
}
