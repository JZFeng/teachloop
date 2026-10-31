package com.teachloop.identity.admin;

import com.teachloop.identity.domain.TeacherInvite;
import com.teachloop.identity.domain.TeacherInviteRepository;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.identity.domain.UserStatus;
import com.teachloop.shared.error.ApiException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** T036：管理员创建账号、教师邀请码、停用账号（CLI 与 HTTP 共用）。 */
@Service
public class AdminService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final UserRepository users;
    private final TeacherInviteRepository invites;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AdminService(UserRepository users, TeacherInviteRepository invites,
                        PasswordEncoder encoder, Clock clock) {
        this.users = users;
        this.invites = invites;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Transactional
    public User createAdmin(String email, String password) {
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.conflict("该邮箱已存在");
        }
        return users.save(new User(UUID.randomUUID(), UserRole.ADMIN, email,
                encoder.encode(password), "管理员", Instant.now(clock)));
    }

    /**
     * 2026-10-27：管理员建普通账号（教师/学生），用于开通测试账号。
     *
     * <p>{@code passwordLoginEnabled=true} 时该账号在生产环境可以用邮箱 + 密码登录
     * （见 {@code PasswordAuthProvider}）；这是**按账号**的白名单，不是对所有人放开。
     * 密码为 null/空时不允许建号——没有密码的账号在 prod 只能走微信，那不是本方法的用途。
     */
    @Transactional
    public User createUser(String email, UserRole role, String displayName, String password,
                           boolean passwordLoginEnabled) {
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.conflict("该邮箱已存在");
        }
        if (password == null || password.isBlank()) {
            throw ApiException.badRequest("测试账号必须设置密码");
        }
        String name = displayName == null || displayName.isBlank()
                ? email.substring(0, email.indexOf('@'))
                : displayName.trim();
        User user = new User(UUID.randomUUID(), role, email, encoder.encode(password), name, Instant.now(clock));
        user.setPasswordLoginEnabled(passwordLoginEnabled);
        return users.save(user);
    }

    /**
     * 2026-10-27：管理员重置某个账号的密码（朋友忘密码时用，不需要旧密码）。
     * 同时把 {@code session_invalid_before} 置为 now()，该账号已有会话全部作废。
     */
    @Transactional
    public User setPassword(String email, String password) {
        User user = users.findByEmail(email).orElseThrow(() -> ApiException.notFound("账号不存在"));
        if (password == null || password.isBlank()) {
            throw ApiException.badRequest("密码不能为空");
        }
        user.setPasswordHash(encoder.encode(password));
        user.setSessionInvalidBefore(Instant.now(clock));
        return users.save(user);
    }

    @Transactional
    public TeacherInvite createTeacherInvite(UUID createdBy, int validDays) {
        String code = newCode();
        return invites.save(new TeacherInvite(UUID.randomUUID(), code, createdBy,
                Instant.now(clock).plusSeconds(validDays * 86400L), Instant.now(clock)));
    }

    @Transactional
    public User disable(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("用户不存在"));
        user.setStatus(UserStatus.DISABLED);
        return user;
    }

    public User find(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("用户不存在"));
    }

    /** AC-3.11：管理端列表视图。状态由服务端算一次，前端不再重复推导。 */
    public record TeacherInviteView(String code, Instant expiresAt, Instant createdAt,
                                    String status, String usedByEmail) {
    }

    /** AC-3.11：时间倒序（新生成的码在前），含状态与兑换人邮箱。 */
    @Transactional(readOnly = true)
    public java.util.List<TeacherInviteView> listTeacherInvites() {
        Instant now = Instant.now(clock);
        return invites.findAllByOrderByCreatedAtDesc().stream()
                .map(inv -> new TeacherInviteView(
                        inv.getCode(),
                        inv.getExpiresAt(),
                        inv.getCreatedAt(),
                        inviteStatus(inv, now),
                        inv.getUsedBy() == null
                                ? null
                                : users.findById(inv.getUsedBy()).map(User::getEmail).orElse(null)))
                .toList();
    }

    /** AC-3.2：未兑换的码可撤销；已兑换的不撤账号（撤账号走停用，AC-3.6）。 */
    @Transactional
    public TeacherInvite revokeTeacherInvite(String code) {
        TeacherInvite invite = invites.findByCode(code == null ? "" : code.trim().toUpperCase())
                .orElseThrow(() -> ApiException.notFound("error.teacherInvite.revokeNotFound"));
        if (invite.getUsedAt() != null) {
            throw ApiException.conflict("error.teacherInvite.used");
        }
        invite.disable();
        return invites.save(invite);
    }

    private static String inviteStatus(TeacherInvite inv, Instant now) {
        if (inv.getUsedAt() != null) {
            return "USED";
        }
        if (inv.isDisabled()) {
            return "REVOKED";
        }
        return inv.getExpiresAt().isAfter(now) ? "ACTIVE" : "EXPIRED";
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
