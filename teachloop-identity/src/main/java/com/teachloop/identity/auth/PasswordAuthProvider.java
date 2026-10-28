package com.teachloop.identity.auth;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.identity.domain.UserStatus;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 生产环境密码登录入口。
 *
 * <p>T032 的原始规矩：生产只允许管理员用邮箱 + 密码登录，教师与学生走微信。
 * 2026-10-27 增加一条**按账号**的白名单：{@code users.password_login_enabled = true} 的账号
 * （管理员用 {@code AdminCli --create-user} 建出来的测试账号）同样可以密码登录。
 *
 * <p>白名单是列而不是 profile 开关，因此「对所有人放开密码登录」仍然被禁止——
 * {@code ProdAuthTest.devLoginDisabledInProd} 里那条「普通教师密码登录返回 503」的断言继续成立。
 */
@Component
public class PasswordAuthProvider {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public PasswordAuthProvider(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    public Optional<User> login(String email, String password) {
        return users.findByEmail(email)
                .filter(u -> u.getRole() == UserRole.ADMIN || u.isPasswordLoginEnabled())
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> u.getPasswordHash() != null)
                .filter(u -> encoder.matches(password, u.getPasswordHash()));
    }
}
