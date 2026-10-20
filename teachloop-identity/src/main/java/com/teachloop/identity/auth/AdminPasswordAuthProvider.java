package com.teachloop.identity.auth;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.identity.domain.UserStatus;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** T032：生产环境唯一可用的密码登录入口——只允许管理员。 */
@Component
public class AdminPasswordAuthProvider {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AdminPasswordAuthProvider(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    public Optional<User> login(String email, String password) {
        return users.findByEmail(email)
                .filter(u -> u.getRole() == UserRole.ADMIN)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> u.getPasswordHash() != null)
                .filter(u -> encoder.matches(password, u.getPasswordHash()));
    }
}
