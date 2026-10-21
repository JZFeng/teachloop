package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T234：会话属性写入必须幂等。
 *
 * <p>Spring Session JDBC 给同一 session 写「新」属性时是纯 INSERT。同一会话的并发请求
 * （登录后浏览器立刻发多个请求）会两边都判定为新增，第二条撞
 * {@code spring_session_attributes_pk} 抛 500。V025 把 INSERT 变成 upsert，
 * 这里直接按 Spring Session 的写法插两次来锁住行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SessionAttributeUpsertTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment env;

    @Test
    @DisplayName("同一 (session, 属性名) 插两次不报错，值以最后一次为准")
    void secondInsertBecomesUpdate() {
        String primaryId = UUID.randomUUID().toString();
        jdbc.update("insert into spring_session (primary_id, session_id, creation_time, last_access_time, "
                        + "max_inactive_interval, expiry_time) values (?, ?, ?, ?, ?, ?)",
                primaryId, UUID.randomUUID().toString(), 1L, 1L, 1800, 99L);

        jdbc.update("insert into spring_session_attributes (session_primary_id, attribute_name, attribute_bytes) "
                + "values (?, ?, ?)", primaryId, "SPRING_SECURITY_CONTEXT",
                "first".getBytes(StandardCharsets.UTF_8));
        // 没有 V025 的触发器时，这一句就是 duplicate key
        jdbc.update("insert into spring_session_attributes (session_primary_id, attribute_name, attribute_bytes) "
                + "values (?, ?, ?)", primaryId, "SPRING_SECURITY_CONTEXT",
                "second".getBytes(StandardCharsets.UTF_8));

        Integer rows = jdbc.queryForObject(
                "select count(*) from spring_session_attributes where session_primary_id = ?",
                Integer.class, primaryId);
        assertThat(rows).isEqualTo(1);
        String value = new String(jdbc.queryForObject(
                "select attribute_bytes from spring_session_attributes where session_primary_id = ?",
                byte[].class, primaryId), StandardCharsets.UTF_8);
        assertThat(value).isEqualTo("second");
    }
}
