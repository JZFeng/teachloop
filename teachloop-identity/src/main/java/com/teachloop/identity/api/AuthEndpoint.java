package com.teachloop.identity.api;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.identity.security.SessionAuthFilter;
import com.teachloop.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthEndpoint {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthEndpoint(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    @PostMapping("/login")
    public AuthDtos.MeView login(@RequestBody @Valid LoginRequest body, HttpServletRequest request) {
        User user = users.findByEmail(body.email())
                .orElseThrow(() -> ApiException.unauthorized("邮箱或密码不正确"));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(body.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("邮箱或密码不正确");
        }
        request.getSession(true).setAttribute(SessionAuthFilter.SESSION_UID, user.getId().toString());
        return me(user);
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

    private AuthDtos.MeView me(User u) {
        return new AuthDtos.MeView(u.getId().toString(), u.getDisplayName(), u.getEmail(), u.getRole(), u.getLocale());
    }
}
