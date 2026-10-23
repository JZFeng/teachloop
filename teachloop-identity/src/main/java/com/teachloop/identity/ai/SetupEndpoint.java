package com.teachloop.identity.ai;

import com.teachloop.identity.api.AuthDtos;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.identity.security.SessionAuthFilter;
import com.teachloop.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 首次管理员初始化（类似 DSH initial setup）：只在没有任何 ADMIN 时开放。 */
@RestController
@RequestMapping("/api/setup")
public class SetupEndpoint {

    public record Status(boolean needsAdmin, boolean aiConfigured, String aiModel) {
    }

    public record CreateAdmin(@NotBlank @Email String email, @NotBlank @Size(min = 8) String password,
                              String displayName, String apiKey) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AiSettingsService aiSettings;
    private final Clock clock;

    public SetupEndpoint(UserRepository users, PasswordEncoder encoder, AiSettingsService aiSettings, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.aiSettings = aiSettings;
        this.clock = clock;
    }

    @GetMapping("/status")
    public Status status() {
        var settings = aiSettings.view();
        return new Status(users.countByRole(UserRole.ADMIN) == 0, settings.configured(), settings.model());
    }

    @PostMapping("/admin")
    @Transactional
    public AuthDtos.MeView createAdmin(@RequestBody @Valid CreateAdmin body, HttpServletRequest request) {
        if (users.countByRole(UserRole.ADMIN) > 0) {
            throw ApiException.conflict("管理员已存在，请直接登录");
        }
        String email = body.email().trim().toLowerCase();
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.conflict("该邮箱已被占用");
        }
        String name = body.displayName() == null || body.displayName().isBlank()
                ? "管理员" : body.displayName().trim();
        String apiKey = body.apiKey() == null ? null : body.apiKey().strip();
        User admin = users.save(new User(UUID.randomUUID(), UserRole.ADMIN, email,
                encoder.encode(body.password()), name, Instant.now(clock)));
        if (apiKey != null && !apiKey.isBlank()) {
            aiSettings.update(admin.getId(), new AiSettingsService.UpdateRequest(
                    "deepseek", null, null, apiKey, null));
        }
        request.getSession(true).setAttribute(SessionAuthFilter.SESSION_UID, admin.getId().toString());
        return new AuthDtos.MeView(admin.getId().toString(), admin.getDisplayName(), admin.getEmail(),
                admin.getRole(), admin.getLocale());
    }
}
