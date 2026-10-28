package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.auth.CodeService;
import com.teachloop.identity.auth.VerificationPurpose;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 009 T522：验证码/令牌的签发、消费、过期、错误计数。 */
@SpringBootTest
@Testcontainers
class CodeServiceTest {

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

    @Autowired CodeService codes;

    @Test
    @DisplayName("发的码能消费一次；第二次是 USED")
    void issueThenConsumeOnce() {
        var issued = codes.issueCode(VerificationPurpose.LOGIN, "email", "a@example.com", Duration.ofMinutes(10));
        assertThat(issued.raw()).hasSize(6);

        var first = codes.consume(VerificationPurpose.LOGIN, "email", "a@example.com", issued.raw());
        assertThat(first.status()).isEqualTo(CodeService.ConsumeStatus.OK);

        var second = codes.consume(VerificationPurpose.LOGIN, "email", "a@example.com", issued.raw());
        assertThat(second.status()).isEqualTo(CodeService.ConsumeStatus.USED);
    }

    @Test
    @DisplayName("错 5 次作废，第 5 次返回 ATTEMPTS_EXCEEDED")
    void fiveWrongAttemptsBurnTheCode() {
        var issued = codes.issueCode(VerificationPurpose.LOGIN, "email", "b@example.com", Duration.ofMinutes(10));
        for (int i = 1; i <= 4; i++) {
            assertThat(codes.consume(VerificationPurpose.LOGIN, "email", "b@example.com", "000000").status())
                    .isEqualTo(CodeService.ConsumeStatus.INVALID);
        }
        assertThat(codes.consume(VerificationPurpose.LOGIN, "email", "b@example.com", "000000").status())
                .isEqualTo(CodeService.ConsumeStatus.ATTEMPTS_EXCEEDED);
        // 已经作废后，连正确的码也不再生效
        assertThat(codes.consume(VerificationPurpose.LOGIN, "email", "b@example.com", issued.raw()).status())
                .isEqualTo(CodeService.ConsumeStatus.ATTEMPTS_EXCEEDED);
    }

    @Test
    @DisplayName("过期码返回 EXPIRED，且不能消费")
    void expiredCode() {
        var issued = codes.issueCode(VerificationPurpose.LOGIN, "email", "c@example.com", Duration.ofSeconds(-1));
        assertThat(codes.consume(VerificationPurpose.LOGIN, "email", "c@example.com", issued.raw()).status())
                .isEqualTo(CodeService.ConsumeStatus.EXPIRED);
    }

    @Test
    @DisplayName("激活令牌是长串且一次有效")
    void activationToken() {
        var token = codes.issueToken(VerificationPurpose.REGISTER_ACTIVATE, "email", "d@example.com",
                Duration.ofHours(24));
        assertThat(token.raw().length()).isGreaterThan(30);
        // 令牌只凭自身就能回查（激活页拿不到邮箱），所以走 inspect/consumeToken
        var peek = codes.inspectToken(VerificationPurpose.REGISTER_ACTIVATE, token.raw());
        assertThat(peek.status()).isEqualTo(CodeService.ConsumeStatus.OK);
        assertThat(peek.target()).isEqualTo("d@example.com");

        assertThat(codes.consumeToken(VerificationPurpose.REGISTER_ACTIVATE, token.raw()).status())
                .isEqualTo(CodeService.ConsumeStatus.OK);
        assertThat(codes.consumeToken(VerificationPurpose.REGISTER_ACTIVATE, token.raw()).status())
                .isEqualTo(CodeService.ConsumeStatus.USED);
    }

    @Test
    @DisplayName("目标不存在返回 NOT_FOUND（用于 404 语义）")
    void unknownTarget() {
        assertThat(codes.consume(VerificationPurpose.LOGIN, "email", "nobody@example.com", "123456").status())
                .isEqualTo(CodeService.ConsumeStatus.NOT_FOUND);
    }
}
