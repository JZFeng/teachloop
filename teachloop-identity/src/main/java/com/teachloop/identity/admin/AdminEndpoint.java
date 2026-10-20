package com.teachloop.identity.admin;

import com.teachloop.identity.domain.TeacherInvite;
import com.teachloop.identity.domain.User;
import com.teachloop.shared.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** T036：管理员的 HTTP 入口；管理员角色由 CLI 创建。 */
@RestController
@RequestMapping("/api/admin")
public class AdminEndpoint {

    private final AdminService admin;

    public AdminEndpoint(AdminService admin) {
        this.admin = admin;
    }

    public record InviteView(String id, String code, String expiresAt) {
    }

    public record UserView(String id, String email, String displayName, String role, String status) {
    }

    @PostMapping("/teacher-invites")
    @Transactional
    public InviteView createInvite(@RequestAttribute("currentUser") CurrentUser me) {
        requireAdmin(me);
        TeacherInvite invite = admin.createTeacherInvite(me.id(), 3);
        return new InviteView(invite.getId().toString(), invite.getCode(), invite.getExpiresAt().toString());
    }

    @GetMapping("/users")
    public List<UserView> users(@RequestAttribute("currentUser") CurrentUser me) {
        requireAdmin(me);
        return List.of();
    }

    @PostMapping("/users/{id}/disable")
    @Transactional
    public UserView disable(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        requireAdmin(me);
        User user = admin.disable(id);
        return new UserView(user.getId().toString(), user.getEmail(), user.getDisplayName(),
                user.getRole().name(), user.getStatus().name());
    }

    private static void requireAdmin(CurrentUser me) {
        if (!me.is("ADMIN")) {
            throw com.teachloop.shared.error.ApiException.forbidden("需要管理员权限");
        }
    }
}
