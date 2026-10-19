package com.teachloop.observability.api;

import com.teachloop.observability.domain.AuditLog;
import com.teachloop.observability.domain.AuditLogRepository;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
public class AuditEndpoint {

    private final AuditLogRepository logs;

    public AuditEndpoint(AuditLogRepository logs) {
        this.logs = logs;
    }

    public record AuditView(String id, String action, String actorId, String createdAt, String detail) {
    }

    @GetMapping
    public List<AuditView> byTarget(@RequestParam String targetType, @RequestParam String targetId) {
        return logs.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId).stream()
                .map(AuditEndpoint::view).toList();
    }

    private static AuditView view(AuditLog l) {
        return new AuditView(l.getId().toString(), l.getAction(),
                l.getActorId() == null ? null : l.getActorId().toString(),
                l.getCreatedAt().toString(), l.getDetail());
    }
}
