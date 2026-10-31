package com.teachloop.identity.api;

import com.teachloop.identity.auth.CodeService;
import com.teachloop.identity.auth.PasswordAuthProvider;
import com.teachloop.identity.auth.VerificationPurpose;
import com.teachloop.identity.auth.WeChatAuthProvider;
import com.teachloop.identity.auth.channel.ChannelRegistry;
import com.teachloop.identity.auth.channel.MessageChannel;
import com.teachloop.identity.messaging.OutboundMessageQueue;
import com.teachloop.identity.domain.TeacherInvite;
import com.teachloop.identity.domain.TeacherInviteRepository;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.identity.domain.UserStatus;
import com.teachloop.identity.profile.ProfileCompletion;
import com.teachloop.identity.security.SessionAuthFilter;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.error.ErrorCode;
import com.teachloop.shared.error.TooManyRequestsException;
import com.teachloop.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthEndpoint {

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    private final UserRepository users;
    private final TeacherInviteRepository invites;
    private final PasswordEncoder passwordEncoder;
    private final PasswordAuthProvider passwordProvider;
    private final WeChatAuthProvider weChatProvider;
    private final CodeService codes;
    private final ChannelRegistry channels;
    private final OutboundMessageQueue outbound;
    private final AuditPort audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthEndpoint(UserRepository users, TeacherInviteRepository invites, PasswordEncoder passwordEncoder,
                        PasswordAuthProvider passwordProvider, WeChatAuthProvider weChatProvider,
                        CodeService codes, ChannelRegistry channels, OutboundMessageQueue outbound,
                        AuditPort audit, Clock clock) {
        this.users = users;
        this.invites = invites;
        this.passwordEncoder = passwordEncoder;
        this.passwordProvider = passwordProvider;
        this.weChatProvider = weChatProvider;
        this.codes = codes;
        this.channels = channels;
        this.outbound = outbound;
        this.audit = audit;
        this.clock = clock;
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record WeChatLoginRequest(@NotBlank String code) {
    }

    /** AC-3.3（2026-10-30）：只认会话，请求体只剩码；邮箱/密码不再需要。 */
    public record TeacherInviteRedeemRequest(@NotBlank String code) {
    }

    public record CodeRequest(@NotBlank String channel, @NotBlank @Email String email) {
    }

    public record CodeVerifyRequest(@NotBlank String channel, @NotBlank @Email String email,
                                    @NotBlank String code) {
    }

    public record PasswordResetVerifyRequest(@NotBlank @Email String email, @NotBlank String code,
                                             @NotBlank @Size(min = 8, message = "新密码至少 8 位") String newPassword) {
    }

    /**
     * 009 T530 / AC-73.11：邮箱 + 密码登录。任何有密码的 ACTIVE 账号都可以，
     * 不再区分「管理员 / 白名单」——未激活与停用由 {@link #signIn} 判 403。
     */
    @PostMapping("/login")
    public AuthDtos.MeView login(@RequestBody @Valid LoginRequest body, HttpServletRequest request) {
        User user = passwordProvider.login(body.email().trim(), body.password())
                .orElseThrow(() -> ApiException.unauthorized("邮箱或密码不正确"));
        return signIn(user, request);
    }

    /** 009 T531：发登录验证码。账号不存在 404、未激活/停用 403，都不发码。 */
    @PostMapping("/login/challenge")
    public Map<String, Object> loginChallenge(@RequestBody @Valid CodeRequest body) {
        User user = requireActiveAccount(body.email());
        return sendCode(user, body.channel(), VerificationPurpose.LOGIN);
    }

    @PostMapping("/login/verify")
    public AuthDtos.MeView loginVerify(@RequestBody @Valid CodeVerifyRequest body, HttpServletRequest request) {
        var result = codes.consume(VerificationPurpose.LOGIN, body.channel(), normalize(body.email()), body.code().trim());
        requireCodeOk(result.status());
        User user = requireActiveAccount(body.email());
        return signIn(user, request);
    }

    /** 009 T533：忘记密码——发码。 */
    @PostMapping("/password-reset/challenge")
    public Map<String, Object> passwordResetChallenge(@RequestBody @Valid CodeRequest body) {
        User user = requireActiveAccount(body.email());
        return sendCode(user, body.channel(), VerificationPurpose.PASSWORD_RESET);
    }

    /** 009 T533：重置密码。**不自动登录**，并把该账号所有已有会话作废。 */
    @PostMapping("/password-reset/verify")
    @Transactional
    public Map<String, Object> passwordResetVerify(@RequestBody @Valid PasswordResetVerifyRequest body) {
        var result = codes.consume(VerificationPurpose.PASSWORD_RESET, "email", normalize(body.email()), body.code().trim());
        requireCodeOk(result.status());
        User user = requireActiveAccount(body.email());
        user.setPasswordHash(passwordEncoder.encode(body.newPassword()));
        user.setSessionInvalidBefore(Instant.now(clock));
        users.save(user);
        return Map.of("status", "RESET");
    }

    /** T031：微信 code 登录；同一 openid 复用账号，未配置 appid/secret 时 503。 */
    @PostMapping("/wechat")
    public AuthDtos.MeView wechat(@RequestBody @Valid WeChatLoginRequest body, HttpServletRequest request) {
        if (!weChatProvider.configured()) {
            throw new ApiException(ErrorCode.UNAVAILABLE, "微信登录未配置");
        }
        return signIn(weChatProvider.loginOrCreate(body.code()), request);
    }

    /**
     * AC-3.3（2026-10-30 改写）：教师邀请码只用于把「**已登录的自己**」升为教师。
     *
     * <p>旧实现是「码 + 邮箱 + 密码 → 新建一个 TEACHER 账号」，两个问题：
     * 一是它在公开端开出一条「未登录也能造教师」的路；二是老师升级时拿不到自己的旧记录
     * （新 user 行 = 空复习队列）。现在只认会话、忽略请求体里的邮箱，
     * 升级就是翻 {@code users.role} 一列，主键不变，学生期的复习状态、词单、偏好全部照旧。
     *
     * <p>约束：非 STUDENT 返回 409；码一次性、3 天过期；成功后立即作废并写审计。
     */
    @PostMapping("/teacher-invite")
    @Transactional
    public AuthDtos.MeView redeemTeacherInvite(@RequestBody @Valid TeacherInviteRedeemRequest body,
                                               @RequestAttribute("currentUser") CurrentUser current) {
        User me = users.findById(current.id())
                .orElseThrow(() -> ApiException.unauthorized("未登录"));
        if (me.getRole() != UserRole.STUDENT) {
            throw ApiException.conflict("error.teacherInvite.notStudent");
        }
        Instant now = Instant.now(clock);
        TeacherInvite invite = invites.findByCode(body.code().trim().toUpperCase())
                .filter(i -> i.usable(now))
                .orElseThrow(() -> ApiException.notFound("error.teacherInvite.notFound"));
        me.setRole(UserRole.TEACHER);
        users.save(me);
        invite.consume(me.getId(), now);
        invites.save(invite);
        // 宪法第二条：审计必须在业务事务内写。
        audit.record(new AuditEvent(me.getId(), "TEACHER_INVITE_REDEEMED", "teacher_invite",
                invite.getId().toString(),
                Map.of("code", invite.getCode(), "from", UserRole.STUDENT.name(),
                        "to", UserRole.TEACHER.name())));
        // 不重建会话：SessionAuthFilter 每个请求都从库里取当前用户，角色即刻生效（AC-3.12）。
        return me(me);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    @GetMapping("/me")
    public AuthDtos.MeView me(@RequestAttribute("currentUser") CurrentUser current) {
        return me(users.findById(current.id()).orElseThrow(() -> ApiException.unauthorized("未登录")));
    }

    private Map<String, Object> sendCode(User user, String channelName, VerificationPurpose purpose) {
        MessageChannel channel = channels.usable(channelName)
                .orElseThrow(() -> ApiException.notFound("该验证方式不可用"));
        long wait = cooldownRemaining(user.getEmail(), purpose);
        if (wait > 0) {
            throw new TooManyRequestsException("请求过于频繁，请稍后再试", wait);
        }
        var issued = codes.issueCode(purpose, channel.channel(), user.getEmail(), CODE_TTL);
        String body = purpose == VerificationPurpose.PASSWORD_RESET
                ? "你正在重设 TeachLoop 密码，验证码：" + issued.raw() + "（10 分钟内有效）\n\n如果不是你本人操作，忽略这封邮件。\n"
                : "你的 TeachLoop 登录验证码：" + issued.raw() + "（10 分钟内有效）\n";
        // 2026-10-28：只入队。接口不再等 SMTP；投递失败由队列退避重试，
        // 用户收不到就再点一次（冷却 60 秒仍由上面拦着）。
        outbound.enqueue(channel.channel(), user.getEmail(), "TeachLoop 验证码", body);
        return Map.of("status", "sent", "resendAfterSeconds", COOLDOWN.toSeconds());
    }

    private long cooldownRemaining(String email, VerificationPurpose purpose) {
        return codes.lastIssuedAt(purpose, email)
                .map(last -> COOLDOWN.minus(Duration.between(last, Instant.now(clock))).toSeconds())
                .filter(seconds -> seconds > 0)
                .orElse(0L);
    }

    private User requireActiveAccount(String rawEmail) {
        User user = users.findByEmail(normalize(rawEmail))
                .orElseThrow(() -> ApiException.notFound("该邮箱还没有注册"));
        if (user.getStatus() == UserStatus.PENDING) {
            throw new ApiException(ErrorCode.ACCOUNT_PENDING, "账号尚未激活，请先点激活邮件里的链接");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw ApiException.forbidden("账号已停用");
        }
        return user;
    }

    private static void requireCodeOk(CodeService.ConsumeStatus status) {
        switch (status) {
            case OK -> { }
            case EXPIRED -> throw ApiException.badRequest("验证码已过期，请重新获取");
            case USED -> throw ApiException.badRequest("验证码已用过，请重新获取");
            case ATTEMPTS_EXCEEDED -> throw new TooManyRequestsException("尝试次数过多，请重新获取验证码", 60);
            case NOT_FOUND, INVALID -> throw ApiException.badRequest("验证码不正确");
        }
    }

    private AuthDtos.MeView signIn(User user, HttpServletRequest request) {
        if (user.getStatus() == UserStatus.PENDING) {
            // 009 AC-73.6：未激活与「被停用」必须分开，否则用户会以为自己被封了。
            throw new ApiException(ErrorCode.ACCOUNT_PENDING, "账号尚未激活，请先点激活邮件里的链接");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw ApiException.forbidden("账号已停用");
        }
        var session = request.getSession(true);
        session.setAttribute(SessionAuthFilter.SESSION_UID, user.getId().toString());
        // 2026-10-27：记录会话建立时间，供「改密码踢会话」比较。
        session.setAttribute(SessionAuthFilter.SESSION_LOGIN_AT, Instant.now(clock));
        return me(user);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase();
    }

    /** T561 / AC-93.1：资料是否完整随 me 一起返回，前端不再靠 403 判断。 */
    private AuthDtos.MeView me(User u) {
        return new AuthDtos.MeView(u.getId().toString(), u.getDisplayName(), u.getEmail(), u.getRole(),
                u.getLocale(), ProfileCompletion.isIncomplete(u));
    }
}
