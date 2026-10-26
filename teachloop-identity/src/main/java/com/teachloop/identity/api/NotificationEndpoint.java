package com.teachloop.identity.api;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.teachloop.identity.domain.Notification;
import com.teachloop.identity.domain.NotificationRepository;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 模板参数按这个顺序传给 MessageFormat：{0}=作业名，{1}=得分，{2}=满分，{3}=场次数，
     * {4}=老师名，{5}=班级名，{6}=词集名，{7}=截止时间，{8}=provider，{9}=连续失败份数，
     * {10}=自动重派份数，{11}=预计探测时间，{12}=最后错误类别，{13}=待批改份数。
     *
     * <p>新增参数只能追加，不能插到前面：模板里用的是序号，老模板改序号会串位。
     */
    private static final List<String> ARG_KEYS = List.of("title", "score", "total", "count",
            "teacher", "className", "setName", "dueAt", "provider", "failures", "requeued",
            "nextProbeAt", "errorKind", "waiting", "toProvider", "streak");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NotificationRepository notifications;
    private final MessageSource messages;
    private final Clock clock;
    /** 截止时间要写进正文（学生要求「请在 10/28 15:00 之前预习」），按部署时区渲染。 */
    private final ZoneId dueZone;

    public NotificationEndpoint(NotificationRepository notifications, MessageSource messages, Clock clock,
                                @Value("${teachloop.notification.due-zone:Asia/Shanghai}") String dueZone) {
        this.notifications = notifications;
        this.messages = messages;
        this.clock = clock;
        this.dueZone = ZoneId.of(dueZone);
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
        Object[] args = args(payload, locale);
        try {
            String rendered = messages.getMessage(key, args, null, locale);
            return rendered != null ? rendered : fallback;
        } catch (NoSuchMessageException ex) {
            return fallback;
        }
    }

    /**
     * 按 ARG_KEYS 顺序取参数；时间类要换成「瞬间 → 部署时区的可读时间」。
     *
     * <p>缺的键传空串而不是 {@code null}：MessageFormat 会把 null 渲染成字面量「null」，
     * 一条「预计 null 自动试探」的通知比少一句细节更糟。
     */
    private Object[] args(Map<String, String> payload, Locale locale) {
        Object[] args = new Object[ARG_KEYS.size()];
        for (int i = 0; i < ARG_KEYS.size(); i++) {
            String key = ARG_KEYS.get(i);
            String raw = payload.get(key);
            Object value = switch (key) {
                case "dueAt", "nextProbeAt" -> dueText(raw, locale);
                case "teacher" -> teacherName(raw, locale);
                default -> raw;
            };
            args[i] = value == null ? "" : value;
        }
        return args;
    }

    /**
     * 老师名：显示名末尾已经带称谓时先去掉，模板再统一补，避免「周杰伦老师老师」。
     *
     * <p>称谓本身是用户可见文案，放在 {@code messages*.properties}
     * （{@code notification.vocab_assignment.teacherSuffix}）；英文模板没有这个后缀，
     * 所以显示名原样保留。
     */
    private String teacherName(String raw, Locale locale) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        String suffix = messages.getMessage("notification.vocab_assignment.teacherSuffix", null, "", locale);
        if (suffix != null && !suffix.isEmpty() && name.endsWith(suffix)) {
            return name.substring(0, name.length() - suffix.length()).trim();
        }
        return name;
    }

    /**
     * 截止时间：payload 里是 ISO-8601 瞬间，正文要写成本地时间。
     *
     * <p>时区取 {@code teachloop.notification.due-zone}（默认 Asia/Shanghai）：学生端页面上的
     * 时间由浏览器本地时区渲染，正文是服务端拼的，必须有一个明确的部署时区，不能跟着 JVM 默认值跑。
     */
    private String dueText(String raw, Locale locale) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                    .withLocale(locale).withZone(dueZone).format(Instant.parse(raw));
        } catch (DateTimeParseException ex) {
            return raw;
        }
    }

    private static String templateKey(String kind, Map<String, String> payload, String field) {
        String base = "notification." + kind.toLowerCase(Locale.ROOT);
        String variant = variant(kind, payload);
        return variant == null ? base + "." + field : base + "." + variant + "." + field;
    }

    /**
     * 同一 kind 需要不同措辞时的细分：考试/作业、是否带分数、发词通知是否有截止时间。
     *
     * <p>2026-10-25：能拿到「班级 / 老师 / 截止时间 / 本人得分」时用带细节的变体，
     * 拿不到（历史行、目录查不到人）就回退到泛化文案——详情是可加的，不能因缺一个字段发不出去。
     */
    private static String variant(String kind, Map<String, String> payload) {
        if ("offering_published".equalsIgnoreCase(kind)) {
            String offeringKind = payload.get("offeringKind");
            if (offeringKind == null || offeringKind.isBlank()) {
                return null;
            }
            String base = offeringKind.toLowerCase(Locale.ROOT);
            boolean detailed = has(payload, "teacher") && has(payload, "className") && has(payload, "dueAt");
            return detailed ? base + "_detail" : base;
        }
        if ("scores_published".equalsIgnoreCase(kind)) {
            if (!has(payload, "score") || !has(payload, "total")) {
                return null;
            }
            return has(payload, "teacher") && has(payload, "className") ? "detail" : "scored";
        }
        if ("ai_not_ready".equalsIgnoreCase(kind)) {
            return has(payload, "dueAt") && has(payload, "waiting") ? "due" : null;
        }
        if ("feedback_published".equalsIgnoreCase(kind)) {
            if (!has(payload, "score")) {
                return null;
            }
            return has(payload, "teacher") && has(payload, "className") ? "detail" : "scored";
        }
        if ("vocab_assignment".equalsIgnoreCase(kind)) {
            // 老行/老调用只有空 payload：走泛化文案，避免拼出「的null老师」。
            boolean detailed = has(payload, "teacher") && has(payload, "className")
                    && has(payload, "setName");
            if (!detailed) {
                return null;
            }
            return payload.get("dueAt") == null ? "detail_nodue" : "detail";
        }
        return null;
    }

    private static boolean has(Map<String, String> payload, String key) {
        String value = payload.get(key);
        return value != null && !value.isBlank();
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
