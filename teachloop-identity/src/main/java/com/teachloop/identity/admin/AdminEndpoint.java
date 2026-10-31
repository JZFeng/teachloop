package com.teachloop.identity.admin;

import com.teachloop.identity.ai.AiSettingsEndpoint;
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

    /**
     * AC-3.11（2026-10-30）：邀请码列表。此前只有 POST，管理员生成后一刷新就再也看不到码，
     * 所以管理页必须配一条读接口；状态（ACTIVE/USED/REVOKED/EXPIRED）由服务端算。
     */
    @GetMapping("/teacher-invites")
    @Transactional(readOnly = true)
    public List<AdminService.TeacherInviteView> teacherInvites(@RequestAttribute("currentUser") CurrentUser me) {
        requireAdmin(me);
        return admin.listTeacherInvites();
    }

    /** AC-3.2：撤销未兑换的码。已兑换的返回 409（撤账号走停用）。 */
    @PostMapping("/teacher-invites/{code}/revoke")
    @Transactional
    public InviteView revokeInvite(@PathVariable String code, @RequestAttribute("currentUser") CurrentUser me) {
        requireAdmin(me);
        TeacherInvite invite = admin.revokeTeacherInvite(code);
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
