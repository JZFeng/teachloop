package com.teachloop.identity.auth;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** T030：开发替身登录，仅在非 prod profile 生效；生产必须返回 503。 */
@Component
public class DevPasswordAuthProvider {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Environment environment;

    public DevPasswordAuthProvider(UserRepository users, PasswordEncoder encoder, Environment environment) {
        this.users = users;
        this.encoder = encoder;
        this.environment = environment;
    }

    public boolean enabled() {
        return !environment.acceptsProfiles(Profiles.of("prod"));
    }

    public Optional<User> login(String email, String password) {
        return users.findByEmail(email)
                // 2026-10-27（009 T520）：不在这里过滤状态——PENDING/DISABLED 由 signIn 统一判 403，
                // 否则未激活账号会退化成「邮箱或密码不正确」（401），用户没法知道该去激活。
                .filter(u -> u.getPasswordHash() != null)
                .filter(u -> encoder.matches(password, u.getPasswordHash()));
    }
}
