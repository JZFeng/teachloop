package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.classroom.domain.ClassEntity;
import com.teachloop.classroom.domain.ClassPay;
import com.teachloop.classroom.domain.ClassRepository;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T566 / US-95：班级收费三列（迁移 V077 已由 S0 写好，本类只验接口行为）。
 *
 * <ol>
 *   <li>AC-95.1：存量班级全部 {@code FREE/0/CNY}，新建班默认也是 {@code FREE/0/CNY}；</li>
 *   <li>AC-95.2：建班不传价格字段 = 今天的行为；传 {@code payMode != FREE} → 400；</li>
 *   <li>AC-95.3：入班遇 {@code PAID} → 409，文案「付费班级暂未开放」。</li>
 * </ol>
 *
 * <p>AC-95.4（前端不出现价格输入）不在 Java 侧验，由 Lead 检查。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ClassPayTest {

    private static final String PASSWORD = "teachloop123";
    /** AC-95.3 的付费班夹具名：第一期没有创建付费班的接口，只能建个免费班再改库。 */
    private static final String PAID_FIXTURE_NAME = "待转付费班";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired
    Environment env;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    ClassRepository classes;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("AC-95.1：迁移后没有班级落在 FREE/0/CNY 之外；新建班默认也是 FREE/0/CNY")
    void defaultsAreFreeZeroCny() throws Exception {
        String base = base();
        // 只统计「不是本类为 AC-95.3 故意改成的付费班」那些行：同一个测试类共用一台库，
        // 方法执行顺序不定，不能用全表断言。
        Integer off = jdbc.queryForObject(
                "select count(*) from classes where name <> ?"
                        + " and (pay_mode <> 'FREE' or price_cents <> 0 or currency <> 'CNY')",
                Integer.class, PAID_FIXTURE_NAME);
        assertThat(off).as("AC-95.1 存量班级必须是 FREE/0/CNY").isZero();

        String teacher = login(base, teacherWithPhone("pay-teacher@teachloop.local"));
        HttpResponse<String> created = request(base, teacher, "POST", "/api/classes", Map.of("name", "收费字段默认班"));
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(200);

        UUID id = UUID.fromString(String.valueOf(Json.parse(created.body()).get("id")));
        ClassEntity stored = classes.findById(id).orElseThrow();
        assertThat(stored.getPayMode()).isEqualTo(ClassPay.Mode.FREE);
        assertThat(stored.getPriceCents()).isZero();
        assertThat(stored.getCurrency()).isEqualTo("CNY");
        assertThat(stored.isPaid()).isFalse();
    }

    @Test
    @DisplayName("AC-95.2：建班传 payMode=PAID（或价格非 0）返回 400，不落库")
    void paidClassCannotBeCreated() throws Exception {
        String base = base();
        String teacher = login(base, teacherWithPhone("pay-teacher@teachloop.local"));
        Long before = jdbc.queryForObject("select count(*) from classes", Long.class);

        HttpResponse<String> paid = request(base, teacher, "POST", "/api/classes",
                Map.of("name", "付费班", "payMode", "PAID"));
        assertThat(paid.statusCode()).as("AC-95.2 付费班建班必须 400").isEqualTo(400);
        assertThat(Json.parse(paid.body()).get("code")).isEqualTo("BAD_REQUEST");

        HttpResponse<String> priced = request(base, teacher, "POST", "/api/classes",
                Map.of("name", "带价格的免费班", "priceCents", 9900));
        assertThat(priced.statusCode()).as("价格非 0 同样 400").isEqualTo(400);

        Long after = jdbc.queryForObject("select count(*) from classes", Long.class);
        assertThat(after).as("被拒的请求不能落库").isEqualTo(before);
    }

    @Test
    @DisplayName("AC-95.3：入班遇 pay_mode=PAID 返回 409「付费班级暂未开放」；免费班照常 200")
    void joiningPaidClassConflicts() throws Exception {
        String base = base();
        String teacher = login(base, teacherWithPhone("pay-teacher@teachloop.local"));
        String student = login(base, studentWithPhone("pay-student@teachloop.local"));

        HttpResponse<String> free = request(base, teacher, "POST", "/api/classes", Map.of("name", "免费班"));
        assertThat(free.statusCode()).withFailMessage(free.body()).isEqualTo(200);
        String freeInvite = String.valueOf(Json.parse(free.body()).get("inviteCode"));

        HttpResponse<String> paid = request(base, teacher, "POST", "/api/classes", Map.of("name", PAID_FIXTURE_NAME));
        assertThat(paid.statusCode()).isEqualTo(200);
        Map<String, Object> paidView = Json.parse(paid.body());
        String paidId = String.valueOf(paidView.get("id"));
        String paidInvite = String.valueOf(paidView.get("inviteCode"));
        // 第一期没有创建付费班的接口，直接改库模拟「第二期留下的存量付费班」。
        jdbc.update("update classes set pay_mode = 'PAID', price_cents = 9900 where id = ?::uuid", paidId);

        HttpResponse<String> joinPaid = request(base, student, "POST", "/api/classes/join",
                Map.of("inviteCode", paidInvite));
        assertThat(joinPaid.statusCode()).as("AC-95.3 付费班入班必须 409").isEqualTo(409);
        assertThat(Json.parse(joinPaid.body()).get("code")).isEqualTo("CONFLICT");
        assertThat(Json.parse(joinPaid.body()).get("message")).as("AC-95.3 文案").isEqualTo("付费班级暂未开放。");

        HttpResponse<String> joinFree = request(base, student, "POST", "/api/classes/join",
                Map.of("inviteCode", freeInvite));
        assertThat(joinFree.statusCode()).as("免费班入班不受影响").isEqualTo(200);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    /** 资料齐全的教师（T561 之后建班要求 profileIncomplete=false，AC-93.4）。 */
    private String teacherWithPhone(String email) {
        users.findByEmail(email).orElseGet(() -> {
            User user = new User(UUID.randomUUID(), UserRole.TEACHER, email,
                    encoder.encode(PASSWORD), "收费测试老师", Instant.now());
            user.setPhone("13800000021");
            return users.save(user);
        });
        return email;
    }

    private String studentWithPhone(String email) {
        users.findByEmail(email).orElseGet(() -> {
            User user = new User(UUID.randomUUID(), UserRole.STUDENT, email,
                    encoder.encode(PASSWORD), "收费测试学生", Instant.now());
            user.setPhone("13800000022");
            return users.save(user);
        });
        return email;
    }

    private String login(String base, String email) throws Exception {
        HttpResponse<String> res = request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", PASSWORD));
        assertThat(res.statusCode()).withFailMessage("登录失败: %s", res.body()).isEqualTo(200);
        return res.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
    }

    private HttpResponse<String> request(String base, String cookie, String method, String path, Object body)
            throws Exception {
        // 文案走 message key：不带 Accept-Language 时 ResourceBundleMessageSource 给英文基线，
        // 所以这里显式要中文，断言 AC-95.3 的中文原文。
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("Accept-Language", "zh-CN");
        if (cookie != null) b.header("Cookie", cookie);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static String write(Object o) { return MAPPER.writeValueAsString(o); }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) { return MAPPER.readValue(s, Map.class); }
    }
}
