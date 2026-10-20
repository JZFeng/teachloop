package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.app.DemoSeedRunner;
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

/** T021：seed 连跑两次结果一致。 */
@SpringBootTest(properties = "teachloop.seed.demo=true")
@Testcontainers
class DemoSeedTest {

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
    DemoSeedRunner seed;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("T021：seed 两次后用户/班级/成员/试卷数量不变")
    void seedIsRepeatable() {
        seed.seed();
        int users = count("select count(*) from users");
        int classes = count("select count(*) from classes");
        int members = count("select count(*) from class_members");
        int papers = count("select count(*) from papers");
        assertThat(users).isGreaterThanOrEqualTo(32);
        assertThat(classes).isGreaterThanOrEqualTo(1);
        assertThat(members).isEqualTo(30);
        assertThat(papers).isGreaterThanOrEqualTo(1);

        seed.seed();
        assertThat(count("select count(*) from users")).isEqualTo(users);
        assertThat(count("select count(*) from classes")).isEqualTo(classes);
        assertThat(count("select count(*) from class_members")).isEqualTo(members);
        assertThat(count("select count(*) from papers")).isEqualTo(papers);
        assertThat(count("select count(*) from objective_items where paper_id = "
                + "(select id from papers where title = '演示试卷')")).isEqualTo(3);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
