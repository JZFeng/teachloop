package com.teachloop.identity.api;

import com.teachloop.identity.auth.CodeService;
import com.teachloop.identity.auth.VerificationPurpose;
import com.teachloop.identity.auth.channel.ChannelRegistry;
import com.teachloop.identity.auth.channel.MessageChannel;
import com.teachloop.identity.messaging.OutboundMessageQueue;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.identity.domain.UserStatus;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 009 T526–T529：注册、激活、重发、通道发现。
 *
 * <p>关键约束：
 * <ul>
 *   <li>注册先落库再发信——否则用户会收到信但库里没有账号；</li>
 *   <li>激活页 GET **不消费令牌**（邮件网关会预取链接），POST 才激活；</li>
 *   <li>PENDING 重复注册重发信，ACTIVE 才返回 409。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class RegistrationEndpoint {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEndpoint.class);
    private static final Duration TOKEN_TTL = Duration.ofHours(24);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final CodeService codes;
    private final ChannelRegistry channels;
    private final OutboundMessageQueue outbound;
    private final Clock clock;
    private final String baseUrl;

    public RegistrationEndpoint(UserRepository users, PasswordEncoder encoder, CodeService codes,
                                ChannelRegistry channels, OutboundMessageQueue outbound, Clock clock,
                                @Value("${teachloop.app.base-url:https://www.teachloop.org}") String baseUrl) {
        this.users = users;
        this.encoder = encoder;
        this.codes = codes;
        this.channels = channels;
        this.outbound = outbound;
        this.clock = clock;
        this.baseUrl = baseUrl;
    }

    public record RegisterRequest(@NotBlank String channel, @NotBlank @Email String email,
                                  @NotBlank @Size(min = 8, message = "密码至少 8 位") String password,
                                  String displayName) {
    }

    public record ActivateRequest(@NotBlank String token) {
    }

    public record ResendRequest(@NotBlank @Email String email) {
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody @Valid RegisterRequest body) {
        String email = normalize(body.email());
        MessageChannel channel = channels.usable(body.channel())
                .orElseThrow(() -> ApiException.notFound("该注册方式不可用"));

        var existing = users.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getStatus() == UserStatus.PENDING) {
                // 没收到信的常见情况：重发，不建第二个账号
                issueAndSend(channel, user, email, false);
                return Map.of("status", "resent", "resendAfterSeconds", RESEND_COOLDOWN.toSeconds());
            }
            throw ApiException.conflict("该邮箱已注册");
        }

        User user = new User(UUID.randomUUID(), UserRole.STUDENT, email, encoder.encode(body.password()),
                displayName(body.displayName(), email), Instant.now(clock));
        user.setStatus(UserStatus.PENDING);
        users.save(user);
        issueAndSend(channel, user, email, true);
        return Map.of("status", "pending", "resendAfterSeconds", RESEND_COOLDOWN.toSeconds());
    }

    /** 激活页展示用：只报告令牌状态，不改变任何数据。 */
    @GetMapping("/activate")
    @Transactional(readOnly = true)
    public Map<String, Object> activatePreview(@RequestParam("token") String token) {
        var state = codes.inspectToken(VerificationPurpose.REGISTER_ACTIVATE, token);
        return switch (state.status()) {
            case OK -> Map.of("status", "OK", "email", mask(state.target()));
            case EXPIRED -> Map.of("status", "EXPIRED");
            case USED -> Map.of("status", "USED");
            default -> Map.of("status", "INVALID");
        };
    }

    @PostMapping("/activate")
    @Transactional
    public Map<String, Object> activate(@RequestBody @Valid ActivateRequest body) {
        var state = codes.inspectToken(VerificationPurpose.REGISTER_ACTIVATE, body.token());
        if (state.status() == CodeService.ConsumeStatus.NOT_FOUND) {
            throw ApiException.badRequest("激活链接无效");
        }
        if (state.status() == CodeService.ConsumeStatus.OK) {
            users.findByEmail(state.target()).ifPresent(user -> {
                if (user.getStatus() == UserStatus.PENDING) {
                    user.setStatus(UserStatus.ACTIVE);
                    users.save(user);
                }
            });
        }
        // 无论 OK 还是已用过，都返回同一个结果：不暴露「这个邮箱是否存在」
        codes.consumeToken(VerificationPurpose.REGISTER_ACTIVATE, body.token());
        return Map.of("status", "ACTIVE");
    }

    @PostMapping("/activate/resend")
    public Map<String, Object> resend(@RequestBody @Valid ResendRequest body) {
        String email = normalize(body.email());
        User user = users.findByEmail(email).orElseThrow(() -> ApiException.notFound("该邮箱没有待激活的账号"));
        if (user.getStatus() != UserStatus.PENDING) {
            throw ApiException.conflict("该邮箱已激活");
        }
        MessageChannel channel = channels.usable("email")
                .orElseThrow(() -> ApiException.notFound("该注册方式不可用"));
        issueAndSend(channel, user, email, false);
        return Map.of("status", "resent");
    }

    /**
     * 令牌与邮件分两步：令牌先落库，邮件只入队（2026-10-28 改异步）。
     *
     * <p>不再「发信失败就作废令牌」——投递交给 {@code OutboundMessageWorker} 按退避重试，
     * 接口不必等 SMTP；用户真收不到可以点「重发激活邮件」，那时会换一个新令牌。
     */
    private void issueAndSend(MessageChannel channel, User user, String email, boolean fresh) {
        // TODO(T532)：60s 冷却 / 1h 5 次 / IP 1h 20 次，接入 rate_limits 分桶后在这里判。
        var token = codes.issueToken(VerificationPurpose.REGISTER_ACTIVATE, channel.channel(), email, TOKEN_TTL);
        String link = baseUrl.replaceAll("/+$", "") + "/activate?token=" + token.raw();
        String body = "你好，\n\n请点下面的链接激活你的 TeachLoop 账号（24 小时内有效）：\n\n"
                + link + "\n\n如果不是你本人操作，忽略这封邮件即可。\n";
        outbound.enqueue(channel.channel(), email, "激活你的 TeachLoop 账号", body);
    }

    private static String displayName(String given, String email) {
        return given == null || given.isBlank() ? email.substring(0, email.indexOf('@')) : given.trim();
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase();
    }

    private static String mask(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        String name = email.substring(0, email.indexOf('@'));
        return (name.length() <= 2 ? name.substring(0, 1) + "*" : name.charAt(0) + "***")
                + email.substring(email.indexOf('@'));
    }
}
