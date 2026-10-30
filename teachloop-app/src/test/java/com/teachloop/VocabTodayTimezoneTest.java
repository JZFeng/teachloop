package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * 011 / T557 / AC-99.1 / 99.2 / 99.3 / 99.4：同一 UTC 时刻，不同 {@code X-Timezone}
 * 调 {@code GET /api/vocab/review/queue} 得到不同的 {@code todayReviewed}。
 *
 * <p>时钟被替换成可拨动的 {@link MutableClock}：整条「日界」链路（端点 → VocabZones →
 * review_logs 计数）都读同一个 UTC 时刻，测的是口径而不是机器时间。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.engine=stub",
                "teachloop.vocab.generation.poll-ms=3600000",
                "teachloop.grading.poll-ms=3600000",
                "teachloop.grading.dispatch-ms=3600000",
                "teachloop.grading.sweep-ms=3600000",
                "teachloop.exam.scan-ms=3600000"
        })
@Testcontainers
class VocabTodayTimezoneTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

    /** 可拨动的 UTC 时钟：默认停在 AC-99.3 的那个瞬间。 */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-10-31T16:01:00Z"));

        void set(String instant) {
            now.set(Instant.parse(instant));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    MutableClock clock;

    private final HttpClient client = HttpClient.newHttpClient();

    private UUID studentId;

    @BeforeEach
    void seedStudent() {
        clock.set("2026-10-31T16:01:00Z");
        String email = "tz-" + UUID.randomUUID() + "@teachloop.local";
        studentId = UUID.randomUUID();
        users.save(new User(studentId, UserRole.STUDENT, email, encoder.encode("teachloop123"),
                "时区学生", Instant.parse("2026-10-01T00:00:00Z")));
        // 三张到期卡（非 NEW，避免每日新卡上限干扰今天已复习的计数）。
        for (int i = 0; i < 3; i++) {
            UUID wordId = UUID.randomUUID();
            jdbc.update("""
                    insert into vocab_words (id, user_id, language, form, normalized_form,
                                             content_status, created_at, updated_at)
                    values (?, ?, 'en', ?, ?, 'READY', now(), now())
                    """, wordId, studentId, "tzword" + i, "tzword" + i);
            jdbc.update("""
                    insert into vocab_progress (word_id, user_id, state, due_at, interval_days, ease,
                                               lapses, reviewed_count, updated_at)
                    values (?, ?, 'REVIEW', ?, 1, 2.5, 0, 1, now())
                    """, wordId, studentId, java.sql.Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));
        }
    }

    private void logReviewed(String instant) {
        jdbc.update("""
                insert into vocab_review_logs (user_id, word_id, rating, reviewed_at)
                values (?, ?, 2, ?)
                """, studentId, UUID.randomUUID(), java.sql.Timestamp.from(Instant.parse(instant)));
    }

    @Test
    @DisplayName("AC-99.3：北京 11-01 00:01 与洛杉矶 10-31 09:01 同一时刻，todayReviewed 分别为 1 与 2")
    void sameInstantDifferentHeader() throws Exception {
        String cookie = login();
        // 北京 10-31 23:30（北京时间当天 0 点之前）→ 北京不算、洛杉矶 10-31 08:30 算
        logReviewed("2026-10-31T15:30:00Z");
        // 北京 11-01 00:00 整 → 两边都算
        logReviewed("2026-10-31T16:00:00Z");

        assertThat(todayReviewed(cookie, "Asia/Shanghai")).isEqualTo(1);
        assertThat(todayReviewed(cookie, "America/Los_Angeles")).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-99.2：非法头（Mars/Base、空串、超长串）仍返回 200，并按产品默认计算")
    void invalidHeaderStill200() throws Exception {
        String cookie = login();
        logReviewed("2026-10-31T15:30:00Z");
        logReviewed("2026-10-31T16:00:00Z");

        assertThat(todayReviewed(cookie, "Mars/Base")).isEqualTo(1);
        assertThat(todayReviewed(cookie, "")).isEqualTo(1);
        assertThat(todayReviewed(cookie, "America/" + "x".repeat(80))).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-99.1：头缺失时读 users.timezone；资料也为空则回落产品默认")
    void storedTimezoneUsedWhenHeaderMissing() throws Exception {
        String cookie = login();
        logReviewed("2026-10-31T15:30:00Z");
        logReviewed("2026-10-31T16:00:00Z");

        assertThat(todayReviewed(cookie, null)).as("资料为空 → 产品默认北京").isEqualTo(1);

        User user = users.findById(studentId).orElseThrow();
        user.setTimezone("America/Los_Angeles");
        users.save(user);

        assertThat(todayReviewed(cookie, null)).as("资料里的洛杉矶").isEqualTo(2);
        assertThat(todayReviewed(cookie, "Mars/Base")).as("非法头回落到资料").isEqualTo(2);
        assertThat(todayReviewed(cookie, "Asia/Shanghai")).as("头仍然优先").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-99.4：洛杉矶 2027-03-14 春令时切换，日界只切一次、不重复计数")
    void springForwardNoDoubleCount() throws Exception {
        String cookie = login();

        // 切换前 1 分钟：洛杉矶 2027-03-13 23:59 PST。当天已复习一条（23:30）。
        clock.set("2027-03-14T07:59:00Z");
        logReviewed("2027-03-14T07:30:00Z");   // 洛杉矶 03-13 23:30 PST
        assertThat(todayReviewed(cookie, "America/Los_Angeles")).as("03-13 当天算一条").isEqualTo(1);

        // 当地 00:00（跳表前）：新的一天从 08:00Z 起算，上一条落在昨天。
        clock.set("2027-03-14T08:00:00Z");
        assertThat(todayReviewed(cookie, "America/Los_Angeles")).as("03-14 0 点起归零").isEqualTo(0);

        // 当地 00:30（跳表仍算 03-14 白天）后复习一条 → 1。
        clock.set("2027-03-14T08:31:00Z");
        logReviewed("2027-03-14T08:30:00Z");
        assertThat(todayReviewed(cookie, "America/Los_Angeles")).as("03-14 只算自己的").isEqualTo(1);

        // 当地 03:00 PDT（已跳表）：同一天，不重置第二次。
        clock.set("2027-03-14T10:00:00Z");
        assertThat(todayReviewed(cookie, "America/Los_Angeles")).as("跳表后仍是同一天，不重复计数").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-99.4：洛杉矶 2027-11-07 冬令时回拨，日界只切一次、不漏计")
    void fallBackNoDoubleCount() throws Exception {
        String cookie = login();

        // 回拨前（洛杉矶 11-06 23:59 PDT）：当天有两条。
        clock.set("2027-11-07T06:59:00Z");
        logReviewed("2027-11-06T07:30:00Z");   // 洛杉矶 11-06 00:30 PDT
        logReviewed("2027-11-07T06:30:00Z");   // 洛杉矶 11-06 23:30 PDT
        assertThat(todayReviewed(cookie, "America/Los_Angeles")).as("11-06 当天算两条").isEqualTo(2);

        // 当地 00:00（回拨前一刻，PDT）：11-07 的日界从 07:00Z 起算，前两条都不算。
        clock.set("2027-11-07T07:00:00Z");
        assertThat(todayReviewed(cookie, "America/Los_Angeles")).as("11-07 0 点起归零").isEqualTo(0);

        // 当地 00:20 复习一条 → 1。
        clock.set("2027-11-07T07:31:00Z");
        logReviewed("2027-11-07T07:20:00Z");
        assertThat(todayReviewed(cookie, "America/Los_Angeles")).as("11-07 只算自己的").isEqualTo(1);

        // 回拨后的 01:00 PST（09:00Z）：仍是同一天，不重复计数。
        clock.set("2027-11-07T09:00:00Z");
        assertThat(todayReviewed(cookie, "America/Los_Angeles")).as("回拨后仍是同一天").isEqualTo(1);
    }

    // ────────────────────────────────────────────────────── helpers

    private long todayReviewed(String cookie, String header) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(base() + "/api/vocab/review/queue"))
                .header("Cookie", cookie)
                .GET();
        if (header != null) {
            builder.header("X-Timezone", header);
        }
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("todayReviewed").asInt();
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private String login() throws Exception {
        String email = users.findById(studentId).orElseThrow().getEmail();
        String body = MAPPER.writeValueAsString(Map.of("email", email, "password", "teachloop123"));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(base() + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
