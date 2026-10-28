package com.teachloop.identity.api;

import com.teachloop.identity.auth.PasswordAuthProvider;
import com.teachloop.identity.auth.DevPasswordAuthProvider;
import com.teachloop.identity.auth.WeChatAuthProvider;
import com.teachloop.identity.domain.TeacherInvite;
import com.teachloop.identity.domain.TeacherInviteRepository;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.identity.domain.UserStatus;
import com.teachloop.identity.security.SessionAuthFilter;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.error.ErrorCode;
import com.teachloop.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthEndpoint {

    private final UserRepository users;
    private final TeacherInviteRepository invites;
    private final PasswordEncoder passwordEncoder;
    private final DevPasswordAuthProvider devProvider;
    private final PasswordAuthProvider prodProvider;
    private final WeChatAuthProvider weChatProvider;
    private final Environment environment;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthEndpoint(UserRepository users, TeacherInviteRepository invites, PasswordEncoder passwordEncoder,
                        DevPasswordAuthProvider devProvider, PasswordAuthProvider prodProvider,
                        WeChatAuthProvider weChatProvider, Environment environment, Clock clock) {
        this.users = users;
        this.invites = invites;
        this.passwordEncoder = passwordEncoder;
        this.devProvider = devProvider;
        this.prodProvider = prodProvider;
        this.weChatProvider = weChatProvider;
        this.environment = environment;
        this.clock = clock;
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record WeChatLoginRequest(@NotBlank String code) {
    }

    public record TeacherInviteRequest(@NotBlank String code, @NotBlank String email,
                                       @NotBlank String password, String displayName) {
    }

    /** 生产只允许管理员密码登录；开发/测试 profile 走 DevProvider（任意角色）。 */
    @PostMapping("/login")
    public AuthDtos.MeView login(@RequestBody @Valid LoginRequest body, HttpServletRequest request) {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        Optional<User> user = prod
                ? prodProvider.login(body.email(), body.password())
                : devProvider.login(body.email(), body.password());
        if (user.isEmpty()) {
            if (prod) {
                throw new ApiException(ErrorCode.UNAVAILABLE, "生产环境密码登录仅对管理员开放");
            }
            throw ApiException.unauthorized("邮箱或密码不正确");
        }
        return signIn(user.get(), request);
    }

    /** T031：微信 code 登录；同一 openid 复用账号，未配置 appid/secret 时 503。 */
    @PostMapping("/wechat")
    public AuthDtos.MeView wechat(@RequestBody @Valid WeChatLoginRequest body, HttpServletRequest request) {
        if (!weChatProvider.configured()) {
            throw new ApiException(ErrorCode.UNAVAILABLE, "微信登录未配置");
        }
        return signIn(weChatProvider.loginOrCreate(body.code()), request);
    }

    /** T036：教师邀请码兑换；码一次性、3 天过期；成功后角色升级为 TEACHER。 */
    @PostMapping("/teacher-invite")
    @Transactional
    public AuthDtos.MeView redeemTeacherInvite(@RequestBody @Valid TeacherInviteRequest body,
                                               HttpServletRequest request) {
        TeacherInvite invite = invites.findByCode(body.code().trim().toUpperCase())
                .filter(i -> i.usable(Instant.now(clock)))
                .orElseThrow(() -> ApiException.notFound("邀请码无效或已过期"));
        String email = body.email().trim();
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.conflict("该邮箱已注册");
        }
        User user = new User(UUID.randomUUID(), UserRole.TEACHER, email,
                passwordEncoder.encode(body.password()), displayName(body, email), Instant.now(clock));
        users.save(user);
        invite.consume(user.getId(), Instant.now(clock));
        invites.save(invite);
        return signIn(user, request);
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

    private static String displayName(TeacherInviteRequest body, String email) {
        return body.displayName() == null || body.displayName().isBlank()
                ? email.substring(0, email.indexOf('@'))
                : body.displayName().trim();
    }

    private AuthDtos.MeView me(User u) {
        return new AuthDtos.MeView(u.getId().toString(), u.getDisplayName(), u.getEmail(), u.getRole(), u.getLocale());
    }
}
