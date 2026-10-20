package com.teachloop.identity.profile;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** T034：资料补全（手机号与邮箱必填；微信用户首次补邮箱）。 */
@RestController
@RequestMapping("/api/profile")
public class ProfileEndpoint {

    private final UserRepository users;

    public ProfileEndpoint(UserRepository users) {
        this.users = users;
    }

    public record ProfileRequest(@NotBlank String phone, @NotBlank String email,
                                 String displayName, String locale) {
    }

    public record ProfileView(String id, String email, String phone, String displayName, String locale) {
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

    private static ProfileView view(User u) {
        return new ProfileView(u.getId().toString(), u.getEmail(), u.getPhone(),
                u.getDisplayName(), u.getLocale());
    }
}
