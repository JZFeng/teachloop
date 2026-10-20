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

    private String newCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
