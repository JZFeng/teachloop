package com.teachloop.identity.port;

import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.port.Ports;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 站内通知端口的最小实现：先只留审计痕迹，等 T208 建好 notifications 表后替换投递实现。
 * 接收方与联系方式不外泄（端口签名只收 offeringId）。
 */
@Component
public class NotificationPortImpl implements Ports.NotificationPort {

    private final AuditPort audit;

    public NotificationPortImpl(AuditPort audit) {
        this.audit = audit;
    }

    @Override
    public void notifyOfferingPublished(UUID offeringId) {
        audit.record(new AuditEvent(null, "notification:offering_published", "offering",
                offeringId.toString(), Map.of("channel", "in_app")));
    }

    @Override
    public void notifyScoresPublished(UUID offeringId) {
        audit.record(new AuditEvent(null, "notification:scores_published", "offering",
                offeringId.toString(), Map.of("channel", "in_app")));
    }
}
