package com.teachloop.identity.api;

import com.teachloop.identity.domain.Notification;
import com.teachloop.identity.domain.NotificationRepository;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 站内通知（T114 / AC-12.4）：当前用户的通知列表、未读计数、标记已读、全部已读。
 *
 * <p>所有查询都以会话里的 {@code currentUser.id()} 为过滤条件，路径里没有用户 id；
 * 标记已读用 {@code findByIdAndRecipientId}，别人的通知同样是 404（不泄漏存在性）。
 * 学生与教师都能访问，不做角色限制。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationEndpoint {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationEndpoint(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    /** 列表：默认最近 50 条，按 created_at 倒序；unread=true 只看未读。 */
    @GetMapping
    public List<NotificationDtos.NotificationView> list(
            @RequestAttribute("currentUser") CurrentUser me,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) Integer limit) {
        Pageable page = PageRequest.of(0, normalizeLimit(limit));
        List<Notification> rows = Boolean.TRUE.equals(unread)
                ? notifications.findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(me.id(), page)
                : notifications.findByRecipientIdOrderByCreatedAtDesc(me.id(), page);
        return rows.stream().map(NotificationEndpoint::view).toList();
    }

    @GetMapping("/unread-count")
    public NotificationDtos.UnreadCountView unreadCount(@RequestAttribute("currentUser") CurrentUser me) {
        return new NotificationDtos.UnreadCountView(notifications.countByRecipientIdAndReadAtIsNull(me.id()));
    }

    /** 标记单条已读；重复调用幂等，保留第一次的 read_at。 */
    @PostMapping("/{id}/read")
    @Transactional
    public NotificationDtos.NotificationView markRead(@PathVariable UUID id,
                                                      @RequestAttribute("currentUser") CurrentUser me) {
        Notification notification = notifications.findByIdAndRecipientId(id, me.id())
                .orElseThrow(() -> ApiException.notFound("通知不存在"));
        notification.markRead(Instant.now(clock));
        notifications.save(notification);
        return view(notification);
    }

    /** 全部标记已读；返回本次真正改动的条数，重复调用返回 0。 */
    @PostMapping("/read-all")
    @Transactional
    public NotificationDtos.ReadAllView readAll(@RequestAttribute("currentUser") CurrentUser me) {
        return new NotificationDtos.ReadAllView(notifications.markAllRead(me.id(), Instant.now(clock)));
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static NotificationDtos.NotificationView view(Notification n) {
        return new NotificationDtos.NotificationView(n.getId().toString(), n.getKind(), n.getTargetType(),
                n.getTargetId(), n.getTitle(), n.getBody(), n.isRead(),
                n.getReadAt() == null ? null : n.getReadAt().toString(),
                n.getCreatedAt().toString());
    }
}
