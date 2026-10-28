package com.teachloop.identity.api;

import com.teachloop.identity.ai.SecretBox;
import com.teachloop.identity.auth.channel.IdentityChannelRepository;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

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
                              Integer dailyQuota, String extra) {
    }

    public record UpdateRequest(boolean enabled, @NotBlank String driver, String fromAddress, String baseUrl,
                                /** null = 不改；"" = 清除；其它 = 新密钥 */ String secret,
                                Integer dailyQuota, String extra) {
    }

    private final IdentityChannelRepository channels;
    private final SecretBox secretBox;
    private final AuditPort audit;

    public IdentityChannelAdminEndpoint(IdentityChannelRepository channels, SecretBox secretBox, AuditPort audit) {
        this.channels = channels;
        this.secretBox = secretBox;
        this.audit = audit;
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

        String cipher = body.secret() == null ? null : (body.secret().isBlank() ? "" : secretBox.encrypt(body.secret()));
        channels.update(channel, body.enabled(), body.driver(), body.fromAddress(), body.baseUrl(),
                cipher, body.extra(), body.dailyQuota(), me.id());

        audit.record(new AuditEvent(me.id(), "identity_channel:update", "identity_channel", channel,
                Map.of("enabled", body.enabled(),
                        "driver", body.driver(),
                        // 只记「密钥是否变更」，绝不记密钥本身（AC-76.5）
                        "secretChanged", cipher != null && !cipher.isEmpty(),
                        "secretCleared", "".equals(cipher))));

        return channels.find(existing.channel()).map(this::view)
                .orElseThrow(() -> ApiException.notFound("通道不存在"));
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
                hasSecret, mask, unreadable, row.dailyQuota(), row.extra());
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
