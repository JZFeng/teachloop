package com.teachloop.identity.port;

import com.teachloop.identity.domain.Notification;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.identity.domain.UserStatus;
import com.teachloop.identity.domain.NotificationRepository;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.port.Ports;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
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

    /** A1：payload 以 JSON 存进 jsonb 列；只含调用方给的短字符串，不做多态处理。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final AuditPort audit;
    private final Clock clock;

    public NotificationPortImpl(NotificationRepository notifications, UserRepository users,
                                AuditPort audit, Clock clock) {
        this.notifications = notifications;
        this.users = users;
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
    /**
     * D-31：给全部启用的管理员发一条通知。调用方（grading）不需要知道管理员是谁，
     * 接收方解析留在 identity；没有管理员时安静返回。
     */
    @Override
    @Transactional
    public void notifyAdmins(Ports.NotificationMessage message) {
        if (message == null) {
            return;
        }
        List<Ports.NotificationMessage> messages = users.findByRole(UserRole.ADMIN).stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(user -> new Ports.NotificationMessage(user.getId(), message.kind(),
                        message.targetType(), message.targetId(), message.title(), message.body(),
                        message.payload()))
                .toList();
        notify(messages);
    }

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
            String title = message.title() == null ? null : message.title().trim();
            String payload = payloadJson(message.payload());
            // A1：有 payload 的通知由读取侧渲染文案，不需要调用方预先拼好标题。
            if ((title == null || title.isEmpty()) && payload == null) {
                log.warn("跳过没有标题也没有 payload 的通知：recipient={} kind={}",
                        message.recipientId(), message.kind());
                continue;
            }
            String kind = message.kind() == null || message.kind().isBlank() ? "notice" : message.kind().trim();
            rows.add(new Notification(UUID.randomUUID(), message.recipientId(), kind,
                    clip(message.targetType(), MAX_TARGET_TYPE), clip(message.targetId(), MAX_TARGET_ID),
                    clip(title, MAX_TITLE), message.body(), payload, now));
        }
        if (!rows.isEmpty()) {
            notifications.saveAll(rows);
            log.debug("写入 {} 条站内通知", rows.size());
        }
    }

    /**
     * payload 序列化；{@code null} 表示「没有 payload，读的时候回退原文」。
     *
     * <p>空 map 要写成 {@code {}} 而不是 null：像「发词」这种模板没有参数，但文案仍然要在读取时
     * 按语言渲染——写成 null 就会被当成历史行，回退到（可能为空的）原文。
     */
    private static String payloadJson(Map<String, String> payload) {
        if (payload == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(payload);
        } catch (JacksonException ex) {
            log.warn("通知 payload 序列化失败，回退原文：{}", ex.getMessage());
            return null;
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
