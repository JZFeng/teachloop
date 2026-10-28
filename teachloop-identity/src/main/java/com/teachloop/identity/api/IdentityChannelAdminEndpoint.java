package com.teachloop.identity.api;

import com.teachloop.identity.ai.SecretBox;
import com.teachloop.identity.auth.channel.ChannelSendException;
import com.teachloop.identity.auth.channel.ChannelTestMail;
import com.teachloop.identity.auth.channel.IdentityChannelRepository;
import com.teachloop.identity.auth.channel.VerificationChannel;
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

    /**
     * 「发送测试邮件」的结果。
     *
     * <p>{@code messageKey} 是可翻译的固定原因（由 messages*.properties 提供），
     * {@code message} 是底层异常的原文（例如 SMTP 返回码），二者最多有一个。
     * {@code target} 是这封信实际发去的地址，成功时前端用它拼「已发送到 X」。
     */
    public record TestResult(boolean ok, String messageKey, String message, String target, long latencyMs) {
    }

    /** 够用的邮箱形状校验：拦「teachloop admin」这类把显示名当发件人的输入。 */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final IdentityChannelRepository channels;
    private final SecretBox secretBox;
    private final AuditPort audit;

    private final List<VerificationChannel> implementations;
    private final ObjectMapper mapper;

    public IdentityChannelAdminEndpoint(IdentityChannelRepository channels, SecretBox secretBox, AuditPort audit,
                                        List<VerificationChannel> implementations, ObjectMapper mapper) {
        this.channels = channels;
        this.secretBox = secretBox;
        this.implementations = implementations;
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
     * 2026-10-28：管理端「发送测试邮件」。
     *
     * <p>只发**已保存**的配置，收件人就是该通道的发件人邮箱——刚在表单里改完还没保存时，
     * 点这个按钮验的是旧配置，所以前端必须提示「先保存再测试」。
     *
     * <p>刻意不要求 {@code enabled=true}：先测通再打开开关才是正常顺序。
     */
    @PostMapping("/{channel}/test")
    public TestResult test(@RequestAttribute("currentUser") CurrentUser me, @PathVariable String channel) {
        requireAdmin(me);
        IdentityChannelRepository.Row row = channels.find(channel)
                .orElseThrow(() -> ApiException.notFound("通道不存在"));
        VerificationChannel impl = implementations.stream()
                .filter(c -> c.channel().equals(channel))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("error.identity.channel.testUnsupported"));
        String to = row.fromAddress();
        if (!impl.configured()) {
            return new TestResult(false, "error.identity.channel.notConfigured", null, to, 0);
        }
        long started = System.nanoTime();
        try {
            impl.send(to, ChannelTestMail.SUBJECT, ChannelTestMail.BODY);
        } catch (ChannelSendException ex) {
            long ms = (System.nanoTime() - started) / 1_000_000;
            audit.record(new AuditEvent(me.id(), "identity_channel:test", "identity_channel", channel,
                    Map.of("ok", false, "latencyMs", ms)));
            return new TestResult(false, null, ex.getMessage(), to, ms);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        audit.record(new AuditEvent(me.id(), "identity_channel:test", "identity_channel", channel,
                Map.of("ok", true, "latencyMs", ms)));
        return new TestResult(true, null, null, to, ms);
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
