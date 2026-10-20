package com.teachloop.identity.port;

import com.teachloop.identity.domain.Notification;
import com.teachloop.identity.domain.NotificationRepository;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.port.Ports;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站内通知端口的唯一实现（T208 / T114）。
 *
 * <p>两个旧方法保留原语义：只写审计痕迹、不臆造接收者（端口签名只有 offeringId，
 * 接收方由 sitting 用 {@link #notify(List)} 解析后显式给出）。新方法负责把消息批量落库，
 * 列表 / 未读计数 / 已读由 {@code NotificationEndpoint} 查询。
 *
 * <p>接收方与联系方式不外泄：identity 暴露的只是存储能力，不读取其他模块的数据。
 */
@Component
public class NotificationPortImpl implements Ports.NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(NotificationPortImpl.class);

    private static final int MAX_TITLE = 200;
    private static final int MAX_TARGET_TYPE = 40;
    private static final int MAX_TARGET_ID = 64;

    private final NotificationRepository notifications;
    private final AuditPort audit;
    private final Clock clock;

    public NotificationPortImpl(NotificationRepository notifications, AuditPort audit, Clock clock) {
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
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

    /**
     * 批量写入站内通知（T114 发布侧由 sitting 调用）。
     *
     * <p>这个端口在发布事务里被调用，所以：一条坏消息只跳过、不抛异常；标题超长按列宽截断，
     * 不让「通知没发出去」把布置发布一起回滚。
     */
    @Override
    @Transactional
    public void notify(List<Ports.NotificationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        List<Notification> rows = new ArrayList<>(messages.size());
        for (Ports.NotificationMessage message : messages) {
            if (message == null || message.recipientId() == null) {
                log.warn("跳过接收者为空的通知：{}", message);
                continue;
            }
            String title = message.title() == null ? "" : message.title().trim();
            if (title.isEmpty()) {
                log.warn("跳过标题为空的通知：recipient={} kind={}", message.recipientId(), message.kind());
                continue;
            }
            String kind = message.kind() == null || message.kind().isBlank() ? "notice" : message.kind().trim();
            rows.add(new Notification(UUID.randomUUID(), message.recipientId(), kind,
                    clip(message.targetType(), MAX_TARGET_TYPE), clip(message.targetId(), MAX_TARGET_ID),
                    clip(title, MAX_TITLE), message.body(), now));
        }
        if (!rows.isEmpty()) {
            notifications.saveAll(rows);
            log.debug("写入 {} 条站内通知", rows.size());
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
