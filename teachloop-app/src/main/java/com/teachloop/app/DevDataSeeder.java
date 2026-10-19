package com.teachloop.app;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开发与验收用的种子账号。生产用 teachloop.seed.enabled=false 关闭。
 * 不写死任何真实密钥，密码仅用于本地演示。
 */
@Component
@ConditionalOnProperty(name = "teachloop.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public DevDataSeeder(UserRepository users, PasswordEncoder encoder, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("teacher@teachloop.local", "示例老师", UserRole.TEACHER);
        seed("student@teachloop.local", "示例学生", UserRole.STUDENT);
    }

    private void seed(String email, String displayName, UserRole role) {
        if (users.findByEmail(email).isPresent()) {
            return;
        }
        User u = new User(UUID.randomUUID(), role, email, encoder.encode("teachloop123"),
                displayName, Instant.now(clock));
        users.save(u);
        log.info("seeded {} account {}", role, email);
    }
}
