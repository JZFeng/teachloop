package com.teachloop.identity.auth;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
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
                // 2026-10-27（009 T530 / AC-73.11）：不再限制角色与白名单，
                // 任何有密码的账号都能密码登录；状态由 signIn 统一判（PENDING/DISABLED → 403）。

                // 2026-10-27（009 T520）：不在这里过滤状态——PENDING/DISABLED 由 signIn 统一判 403，
                // 否则未激活账号会退化成「邮箱或密码不正确」（401），用户没法知道该去激活。
                .filter(u -> u.getPasswordHash() != null)
                .filter(u -> encoder.matches(password, u.getPasswordHash()));
    }
}
