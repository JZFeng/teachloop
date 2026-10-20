package com.teachloop.identity.auth;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserStatus;
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
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> u.getPasswordHash() != null)
                .filter(u -> encoder.matches(password, u.getPasswordHash()));
    }
}
