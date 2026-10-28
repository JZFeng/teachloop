package com.teachloop.identity.profile;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.security.SessionAuthFilter;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/** T034：资料补全（手机号与邮箱必填；微信用户首次补邮箱）。 */
@RestController
@RequestMapping("/api/profile")
public class ProfileEndpoint {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public ProfileEndpoint(UserRepository users, PasswordEncoder encoder, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
    }

    public record ProfileRequest(@NotBlank String phone, @NotBlank String email,
                                 String displayName, String locale) {
    }

    public record ProfileView(String id, String email, String phone, String displayName, String locale,
                              boolean hasPassword) {
    }

    /** 2026-10-27：自助改密码。与资料 PUT 分开，凭据不混进资料字段。 */
    public record PasswordChangeRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    @GetMapping
    public ProfileView get(@RequestAttribute("currentUser") CurrentUser me) {
        return view(load(me.id()));
    }

    @PutMapping
    @Transactional
    public ProfileView update(@RequestAttribute("currentUser") CurrentUser me,
                              @RequestBody @Valid ProfileRequest body) {
        User user = load(me.id());
        String email = body.email().trim();
        users.findByEmail(email).filter(other -> !other.getId().equals(user.getId()))
                .ifPresent(other -> { throw ApiException.conflict("该邮箱已被使用"); });
        user.setEmail(email);
        user.setPhone(body.phone().trim());
        if (body.displayName() != null && !body.displayName().isBlank()) {
            user.setDisplayName(body.displayName().trim());
        }
        if (body.locale() != null && !body.locale().isBlank()) {
            user.setLocale(body.locale());
        }
        return view(users.save(user));
    }

    private User load(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.unauthorized("未登录"));
    }

    @PostMapping("/password")
    @Transactional
    public java.util.Map<String, Boolean> changePassword(@RequestAttribute("currentUser") CurrentUser me,
                                                         @RequestBody @Valid PasswordChangeRequest body,
                                                         HttpServletRequest request) {
        User user = load(me.id());
        if (user.getPasswordHash() == null) {
            throw ApiException.badRequest("该账号没有密码，请用验证码登录");
        }
        if (!encoder.matches(body.oldPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("原密码不正确");
        }
        if (body.newPassword().length() < 8) {
            throw ApiException.badRequest("新密码至少 8 位");
        }
        if (encoder.matches(body.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("新密码不能与原密码相同");
        }
        Instant now = Instant.now(clock);
        user.setPasswordHash(encoder.encode(body.newPassword()));
        user.setSessionInvalidBefore(now);
        users.save(user);
        // 改密码的人自己不掉线：把当前会话的登录时间刷新到 now。
        var session = request.getSession(false);
        if (session != null) {
            session.setAttribute(SessionAuthFilter.SESSION_LOGIN_AT, now);
        }
        return java.util.Map.of("changed", true);
    }

    private static ProfileView view(User u) {
        return new ProfileView(u.getId().toString(), u.getEmail(), u.getPhone(),
                u.getDisplayName(), u.getLocale(), u.getPasswordHash() != null);
    }
}
