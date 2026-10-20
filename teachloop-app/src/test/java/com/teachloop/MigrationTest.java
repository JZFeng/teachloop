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
                "rate_limits", "teacher_invites", "daily_stats", "ai_settings");

        Integer activeIndex = jdbc.queryForObject(
                "select count(*) from pg_indexes where tablename = 'submissions' and indexname = 'submissions_active_uk'",
                Integer.class);
        assertThat(activeIndex).isEqualTo(1);

        int before = applied.length;
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().applied()).hasSize(before);
    }
}
