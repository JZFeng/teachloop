package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T020：空库 → 全部迁移成功；关键表/索引存在；重复 migrate 幂等。 */
@SpringBootTest
@Testcontainers
class MigrationTest {

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
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("T020：迁移全部成功、关键表/索引存在、重复执行无待迁移")
    void migrationsApplyAndAreIdempotent() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).isNotEmpty();
        assertThat(applied).allSatisfy(m -> assertThat(m.getState().isApplied()).isTrue());
        assertThat(flyway.info().pending()).isEmpty();

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("users", "classes", "class_members", "papers", "paper_sections",
                "objective_items", "offerings", "submissions", "objective_answers", "grading_runs",
                "reviews", "item_explanations", "ai_calls", "audit_logs", "notifications",
                "rate_limits", "teacher_invites", "daily_stats", "ai_settings",
                // 009-registration：验证码与通道、身份表
                "verification_codes", "identity_channels", "user_identities");

        Integer identityIndex = jdbc.queryForObject(
                "select count(*) from pg_indexes where tablename = 'user_identities' and indexname = 'user_identities_uk'",
                Integer.class);
        assertThat(identityIndex).as("身份唯一性（channel+external_id）").isEqualTo(1);

        Integer channelSeed = jdbc.queryForObject(
                "select count(*) from identity_channels", Integer.class);
        assertThat(channelSeed).as("三行通道初始数据").isEqualTo(3);

        Integer activeIndex = jdbc.queryForObject(
                "select count(*) from pg_indexes where tablename = 'submissions' and indexname = 'submissions_active_uk'",
                Integer.class);
        assertThat(activeIndex).isEqualTo(1);

        // D-14：作业不引用试卷，所以 paper_id 必须可空；kind 与内容来源由 check 约束锁死。
        String paperNullable = jdbc.queryForObject(
                "select is_nullable from information_schema.columns "
                        + "where table_schema = 'public' and table_name = 'offerings' and column_name = 'paper_id'",
                String.class);
        assertThat(paperNullable).as("offerings.paper_id 必须是可空的").isEqualTo("YES");

        String contentCheck = jdbc.queryForObject(
                "select pg_get_constraintdef(oid) from pg_constraint "
                        + "where conname = 'offerings_kind_content_ck'",
                String.class);
        assertThat(contentCheck)
                .as("kind 必须与内容来源一一对应（D-14）")
                .contains("CHECK")
                .contains("ASSIGNMENT")
                .containsIgnoringCase("paper_id is null");

        int before = applied.length;
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().applied()).hasSize(before);
    }
}
