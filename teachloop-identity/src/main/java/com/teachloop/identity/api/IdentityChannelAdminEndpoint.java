package com.teachloop.identity.api;

import com.teachloop.identity.ai.SecretBox;
import com.teachloop.identity.auth.channel.ChannelTestMail;
import com.teachloop.identity.auth.channel.IdentityChannelRepository;
import com.teachloop.identity.auth.channel.MessageChannel;
import com.teachloop.identity.messaging.OutboundMessageQueue;
import com.teachloop.identity.messaging.OutboundMessageRepository;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 009 T534：通道配置的管理端。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li>只有 ADMIN 能进（SecurityConfig 已按 /api/admin/** 收口，这里再兜一层）；</li>
 *   <li>密钥**永不回明文**，只回 {@code hasSecret} 与掩码；解不开时 {@code secretUnreadable=true}；</li>
 *   <li>保存后**下一次请求即生效**——读配置本来就不缓存（AC-76.2）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/identity-channels")
public class IdentityChannelAdminEndpoint {

    public record ChannelView(String channel, boolean enabled, String driver, String fromAddress,
                              String baseUrl, boolean hasSecret, String secretMask, boolean secretUnreadable,
                              Integer dailyQuota, JsonNode extra) {
    }

    public record UpdateRequest(boolean enabled, @NotBlank String driver, String fromAddress, String baseUrl,
                                /** null = 不改；"" = 清除；其它 = 新密钥 */ String secret,
                                Integer dailyQuota, JsonNode extra) {
    }

    /** 「发送测试邮件」入队回执：前端拿 id 轮询 {@code GET /{channel}/test/{id}}。 */
    public record TestQueued(UUID id, String target) {
    }

    /** 投递状态：PENDING / SENT / FAILED；FAILED 时 {@code message} 是底层错误原文。 */
    public record TestStatus(String status, String target, String message) {
    }

    /** 够用的邮箱形状校验：拦「teachloop admin」这类把显示名当发件人的输入。 */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final IdentityChannelRepository channels;
    private final SecretBox secretBox;
    private final AuditPort audit;

    private final List<MessageChannel> implementations;
    private final OutboundMessageQueue outbound;
    private final ObjectMapper mapper;

    public IdentityChannelAdminEndpoint(IdentityChannelRepository channels, SecretBox secretBox, AuditPort audit,
                                        List<MessageChannel> implementations, OutboundMessageQueue outbound,
                                        ObjectMapper mapper) {
        this.channels = channels;
        this.secretBox = secretBox;
        this.implementations = implementations;
        this.outbound = outbound;
        this.audit = audit;
        this.mapper = mapper;
    }

    @GetMapping
    public List<ChannelView> list(@RequestAttribute("currentUser") CurrentUser me) {
        requireAdmin(me);
        return channels.findAll().stream().map(this::view).toList();
    }

    @PutMapping("/{channel}")
    @Transactional
    public ChannelView update(@RequestAttribute("currentUser") CurrentUser me,
                              @PathVariable String channel,
                              @RequestBody UpdateRequest body) {
        requireAdmin(me);
        IdentityChannelRepository.Row existing = channels.find(channel)
                .orElseThrow(() -> ApiException.notFound("通道不存在"));
        validateSmtpConfig(channel, body, existing);

        String cipher = body.secret() == null ? null : (body.secret().isBlank() ? "" : secretBox.encrypt(body.secret()));
        channels.update(channel, body.enabled(), body.driver(), body.fromAddress(), body.baseUrl(),
                cipher, body.extra() == null ? null : body.extra().toString(), body.dailyQuota(), me.id());

        audit.record(new AuditEvent(me.id(), "identity_channel:update", "identity_channel", channel,
                Map.of("enabled", body.enabled(),
                        "driver", body.driver(),
                        // 只记「密钥是否变更」，绝不记密钥本身（AC-76.5）
                        "secretChanged", cipher != null && !cipher.isEmpty(),
                        "secretCleared", "".equals(cipher))));

        return channels.find(existing.channel()).map(this::view)
                .orElseThrow(() -> ApiException.notFound("通道不存在"));
    }

    /**
     * 2026-10-28：管理端「发送测试邮件」——只入队，立即返回消息 id。
     *
     * <p>投递是异步的（`OutboundMessageWorker`），前端拿 id 轮询 {@code /{channel}/test/{id}}
     * 直到 SENT / FAILED，再告诉用户「真发出去了没有」。收件人是该通道的发件人邮箱，
     * 所以只发**已保存**的配置；刚改完还没保存时测的是旧配置（页面有提示）。
     *
     * <p>刻意不要求 {@code enabled=true}：先测通再打开开关才是正常顺序。
     */
    @PostMapping("/{channel}/test")
    public TestQueued test(@RequestAttribute("currentUser") CurrentUser me, @PathVariable String channel) {
        requireAdmin(me);
        IdentityChannelRepository.Row row = channels.find(channel)
                .orElseThrow(() -> ApiException.notFound("通道不存在"));
        MessageChannel impl = implementations.stream()
                .filter(c -> c.channel().equals(channel))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("error.identity.channel.testUnsupported"));
        if (!impl.configured()) {
            throw ApiException.badRequest("error.identity.channel.notConfigured");
        }
        String to = row.fromAddress();
        UUID id = outbound.enqueue(channel, to, ChannelTestMail.SUBJECT, ChannelTestMail.BODY);
        audit.record(new AuditEvent(me.id(), "identity_channel:test", "identity_channel", channel,
                Map.of("queued", id.toString())));
        return new TestQueued(id, to);
    }

    /** 测试投递状态查询；页面每 800ms 拉一次，最多等 30 秒。 */
    @GetMapping("/{channel}/test/{id}")
    public TestStatus testStatus(@RequestAttribute("currentUser") CurrentUser me, @PathVariable String channel,
                                 @PathVariable UUID id) {
        requireAdmin(me);
        OutboundMessageRepository.Delivery delivery = outbound.find(id)
                .filter(d -> d.channel().equals(channel))
                .orElseThrow(() -> ApiException.notFound("error.identity.channel.testNotFound"));
        return new TestStatus(delivery.status().name(), delivery.target(), delivery.lastError());
    }

    /**
     * 2026-10-28 Bug（P1，仅生产复现）：以前 PUT 返回 200 就等于「配好了」。
     * 生产把发件人写成 {@code teachloop admin}（显示名而不是邮箱）也能保存成功，
     * 再赶上开关没勾，注册页就成了「填完三项才 404」。
     *
     * <p>守住「启用 = 真的能发」：发件人必须是邮箱地址，服务地址与可解开的授权码不能缺。
     * 关闭通道时只校验格式，不要求齐全——半配一半也能先存着。
     */
    private void validateSmtpConfig(String channel, UpdateRequest body,
                                    IdentityChannelRepository.Row existing) {
        if (!"email".equals(channel) || !"smtp".equals(body.driver())) {
            return;
        }
        String from = body.fromAddress() == null ? "" : body.fromAddress().trim();
        if (!from.isEmpty() && !EMAIL.matcher(from).matches()) {
            throw ApiException.badRequest("error.identity.channel.fromFormat");
        }
        if (!body.enabled()) {
            return;
        }
        if (from.isEmpty()) {
            throw ApiException.badRequest("error.identity.channel.fromRequired");
        }
        if (body.baseUrl() == null || body.baseUrl().isBlank()) {
            throw ApiException.badRequest("error.identity.channel.baseUrlRequired");
        }
        boolean newSecret = body.secret() != null && !body.secret().isBlank();
        boolean storedSecretUsable = body.secret() == null && decryptable(existing.secretCipher());
        if (!newSecret && !storedSecretUsable) {
            throw ApiException.badRequest("error.identity.channel.secretRequired");
        }
    }

    private boolean decryptable(String cipher) {
        if (cipher == null || cipher.isBlank()) {
            return false;
        }
        try {
            return secretBox.decrypt(cipher) != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private ChannelView view(IdentityChannelRepository.Row row) {
        boolean hasSecret = row.secretCipher() != null && !row.secretCipher().isBlank();
        String mask = null;
        boolean unreadable = false;
        if (hasSecret) {
            try {
                String plain = secretBox.decrypt(row.secretCipher());
                mask = plain == null ? null : "•".repeat(8) + plain.substring(Math.max(0, plain.length() - 2));
                unreadable = plain == null;
            } catch (RuntimeException ex) {
                unreadable = true;
            }
        }
        return new ChannelView(row.channel(), row.enabled(), row.driver(), row.fromAddress(), row.baseUrl(),
                hasSecret, mask, unreadable, row.dailyQuota(), extra(row.extra()));
    }

    /** jsonb 列在接口上回成对象，前端不用再对一段 JSON 文本做二次解析。 */
    private JsonNode extra(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void requireAdmin(CurrentUser me) {
        if (me == null || !me.is("ADMIN")) {
            throw ApiException.forbidden("需要管理员权限");
        }
    }

    /** 供测试与前端复用的常量（保持驱动名稳定）。 */
    public static final List<String> DRIVERS = List.of(
            "smtp", "http-api", "aliyun-sms", "wechat_web", "wechat_mp");

    @SuppressWarnings("unused")
    private static UUID unused() {
        return null;
    }
}
