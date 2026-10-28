package com.teachloop.identity.admin;

import com.teachloop.identity.domain.UserRole;
import java.security.SecureRandom;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 管理员命令行。用法：
 * <pre>
 *   java -jar teachloop-app.jar --create-admin=admin@example.com
 *   java -jar teachloop-app.jar --create-user=friend@example.com --role=STUDENT --display-name=小明
 * </pre>
 *
 * <p>密码取环境变量：{@code --create-admin} 用 {@code ADMIN_PASSWORD}，
 * {@code --create-user} 用 {@code USER_PASSWORD}；未提供则生成一次性随机密码并打印到 stdout
 * （不写日志）。
 *
 * <p>2026-10-27：{@code --create-user} 建的账号 {@code password_login_enabled=true}，
 * 因此可以在生产环境用邮箱 + 密码登录（朋友试用的测试账号）。普通教师/学生不走这条白名单。
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
        if (args.containsOption("create-admin")) {
            createAdmin(args);
        }
        if (args.containsOption("create-user")) {
            createUser(args);
        }
        if (args.containsOption("set-password")) {
            setPassword(args);
        }
    }

    private void createAdmin(ApplicationArguments args) {
        String email = firstValue(args, "create-admin");
        if (email == null) {
            log.error("--create-admin 需要邮箱，例如 --create-admin=admin@example.com");
            return;
        }
        String password = System.getenv("ADMIN_PASSWORD");
        boolean generated = password == null || password.isBlank();
        if (generated) {
            password = randomPassword();
        }
        admin.createAdmin(email, password);
        if (generated) {
            System.out.println("CREATED ADMIN " + email + " PASSWORD " + password);
            System.out.println("请立即保存该密码，并修改 ADMIN_PASSWORD 后再次部署。");
        } else {
            System.out.println("CREATED ADMIN " + email);
        }
    }

    /**
     * 2026-10-27：建测试/受邀账号。默认 STUDENT，默认允许密码登录。
     * 不建班级、不发邀请码——只把账号和密码准备好，其他由人在界面里做。
     */
    private void createUser(ApplicationArguments args) {
        String email = firstValue(args, "create-user");
        if (email == null) {
            log.error("--create-user 需要邮箱，例如 --create-user=friend@example.com");
            return;
        }
        String roleRaw = firstValue(args, "role");
        UserRole role;
        try {
            role = roleRaw == null ? UserRole.STUDENT : UserRole.valueOf(roleRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.error("--role 只能是 STUDENT / TEACHER / ADMIN，收到：{}", roleRaw);
            return;
        }
        if (role == UserRole.ADMIN) {
            log.error("--role=ADMIN 请改用 --create-admin；--create-user 不允许建管理员");
            return;
        }
        boolean passwordLogin = !"false".equalsIgnoreCase(firstValue(args, "password-login"));
        String displayName = firstValue(args, "display-name");

        String password = System.getenv("USER_PASSWORD");
        boolean generated = password == null || password.isBlank();
        if (generated) {
            password = randomPassword();
        }
        admin.createUser(email, role, displayName, password, passwordLogin);
        if (generated) {
            System.out.println("CREATED USER " + email + " ROLE " + role + " PASSWORD " + password);
            System.out.println("请立即保存该密码；该账号可在生产环境用邮箱 + 密码登录。");
        } else {
            System.out.println("CREATED USER " + email + " ROLE " + role);
        }
    }

    /**
     * 2026-10-27：管理员重置密码。用法
     * {@code --set-password=email}，密码取 {@code USER_PASSWORD}，缺省随机生成并打印。
     */
    private void setPassword(ApplicationArguments args) {
        String email = firstValue(args, "set-password");
        if (email == null) {
            log.error("--set-password 需要邮箱，例如 --set-password=friend@example.com");
            return;
        }
        String password = System.getenv("USER_PASSWORD");
        boolean generated = password == null || password.isBlank();
        if (generated) {
            password = randomPassword();
        }
        admin.setPassword(email, password);
        if (generated) {
            System.out.println("RESET PASSWORD " + email + " PASSWORD " + password);
            System.out.println("该账号已有会话已全部作废，请把新密码发给本人。");
        } else {
            System.out.println("RESET PASSWORD " + email);
        }
    }

    private static String firstValue(ApplicationArguments args, String option) {
        if (!args.containsOption(option)) {
            return null;
        }
        return args.getOptionValues(option).stream().findFirst()
                .map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
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
