package com.teachloop.identity.admin;

import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * T036：管理员命令行。用法：
 *   java -jar teachloop-app.jar --create-admin=admin@example.com
 * 密码取 ADMIN_PASSWORD 环境变量；未提供则生成一次性随机密码并打印到 stdout（不写日志）。
 */
@Component
public class AdminCli implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminCli.class);
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final AdminService admin;

    public AdminCli(AdminService admin) {
        this.admin = admin;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("create-admin")) {
            return;
        }
        String email = args.getOptionValues("create-admin").stream().findFirst().orElse("");
        if (email.isBlank()) {
            log.error("--create-admin 需要邮箱，例如 --create-admin=admin@example.com");
            return;
        }
        String password = System.getenv("ADMIN_PASSWORD");
        boolean generated = password == null || password.isBlank();
        if (generated) {
            password = randomPassword();
        }
        admin.createAdmin(email.trim(), password);
        if (generated) {
            System.out.println("CREATED ADMIN " + email.trim() + " PASSWORD " + password);
            System.out.println("请立即保存该密码，并修改 ADMIN_PASSWORD 后再次部署。");
        } else {
            System.out.println("CREATED ADMIN " + email.trim());
        }
    }

    private static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
