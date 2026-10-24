package com.teachloop.identity.api;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.teachloop.identity.domain.Notification;
import com.teachloop.identity.domain.NotificationRepository;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
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
 *
 * <p>A1（2026-10-24）：通知只存 {@code kind + payload}，<b>读取时</b>按请求的 Accept-Language
 * 渲染标题正文（模板在 {@code messages*.properties}）。历史行没有 payload，直接回退存的原文。
 * 这样语言永远跟着学生当前界面走，不再依赖写入那一刻的判断。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationEndpoint {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    /** 模板参数按这个顺序传给 MessageFormat：{0}=作业名，{1}=得分，{2}=满分。 */
    private static final List<String> ARG_KEYS = List.of("title", "score", "total");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NotificationRepository notifications;
    private final MessageSource messages;
    private final Clock clock;

    public NotificationEndpoint(NotificationRepository notifications, MessageSource messages, Clock clock) {
        this.notifications = notifications;
        this.messages = messages;
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
        return rows.stream().map(this::view).toList();
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

    private NotificationDtos.NotificationView view(Notification n) {
        Locale locale = LocaleContextHolder.getLocale();
        return new NotificationDtos.NotificationView(n.getId().toString(), n.getKind(), n.getTargetType(),
                n.getTargetId(), text(n, "title", locale), text(n, "body", locale), n.isRead(),
                n.getReadAt() == null ? null : n.getReadAt().toString(),
                n.getCreatedAt().toString());
    }

    /**
     * 按 kind（+ variant）取模板渲染；没有 payload 或没有模板时回退到落库原文。
     *
     * <p>回退是给历史行和未知 kind 用的：宁可显示旧文案，也不显示一个模板 key。
     */
    private String text(Notification n, String field, Locale locale) {
        Map<String, String> payload = payload(n.getPayloadJson());
        String fallback = "title".equals(field) ? n.getTitle() : n.getBody();
        if (payload == null) {
            return fallback;
        }
        String key = templateKey(n.getKind(), payload, field);
        Object[] args = ARG_KEYS.stream().map(payload::get).toArray();
        try {
            String rendered = messages.getMessage(key, args, null, locale);
            return rendered != null ? rendered : fallback;
        } catch (NoSuchMessageException ex) {
            return fallback;
        }
    }

    private static String templateKey(String kind, Map<String, String> payload, String field) {
        String base = "notification." + kind.toLowerCase(Locale.ROOT);
        String variant = variant(kind, payload);
        return variant == null ? base + "." + field : base + "." + variant + "." + field;
    }

    /** 同一 kind 需要不同措辞时的细分：考试/作业、是否带分数。 */
    private static String variant(String kind, Map<String, String> payload) {
        if ("offering_published".equalsIgnoreCase(kind)) {
            String offeringKind = payload.get("offeringKind");
            return offeringKind == null || offeringKind.isBlank()
                    ? null : offeringKind.toLowerCase(Locale.ROOT);
        }
        if ("feedback_published".equalsIgnoreCase(kind)) {
            return payload.get("score") == null ? null : "scored";
        }
        return null;
    }

    private static Map<String, String> payload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (Exception ex) {
            return null;
        }
    }
}
