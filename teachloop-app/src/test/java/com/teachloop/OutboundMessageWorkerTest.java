package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.ai.SecretBox;
import com.teachloop.identity.auth.channel.ChannelSendException;
import com.teachloop.identity.auth.channel.MessageChannel;
import com.teachloop.identity.messaging.OutboundMessageQueue;
import com.teachloop.identity.messaging.OutboundMessageRepository;
import com.teachloop.identity.messaging.OutboundMessageWorker;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * 2026-10-28：出站队列的投递语义（成功 / 退避重试 / 用尽重试）。
 *
 * <p>为什么要单独测：`@Async` 丢了消息没人知道，队列一旦把「重试」写错就是静默丢信。
 * 这里用假通道把三条路径钉死，不碰真实 SMTP。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OutboundMessageWorkerTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // 本类只手动调 dispatchBatch()；把定时器推到 1 小时，避免它和断言抢消息。
        r.add("teachloop.outbound.poll-ms", () -> "3600000");
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SecretBox secretBox;
    @Autowired OutboundMessageRepository repository;

    private final FakeChannel channel = new FakeChannel();
    private OutboundMessageQueue queue;
    private OutboundMessageWorker worker;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbound_messages");
        channel.received.clear();
        channel.failWith = null;
        queue = new OutboundMessageQueue(repository, secretBox);
        worker = new OutboundMessageWorker(repository, secretBox, List.of(channel), Clock.systemUTC());
    }

    @Test
    @DisplayName("成功投递：正文从密文还原、状态 SENT、正文列被清空")
    void deliversAndClearsBody() {
        UUID id = queue.enqueue("email", "someone@test.local", "主题", "正文里有令牌 token=abc");

        assertThat(worker.dispatchBatch()).isEqualTo(1);

        assertThat(channel.received).containsExactly("正文里有令牌 token=abc");
        assertThat(repository.find(id).orElseThrow().status()).isEqualTo(OutboundMessageRepository.Status.SENT);
        assertThat(jdbc.queryForObject(
                "select body_cipher from outbound_messages where id = ?", String.class, id)).isNull();
    }

    @Test
    @DisplayName("首次失败：留在 PENDING、attempts=1、下次时间在未来（退避 30 秒）")
    void firstFailureSchedulesRetry() {
        channel.failWith = new ChannelSendException("SMTP 未按预期应答：535");
        UUID id = queue.enqueue("email", "someone@test.local", "主题", "正文");

        assertThat(worker.dispatchBatch()).isEqualTo(1);

        var delivery = repository.find(id).orElseThrow();
        assertThat(delivery.status()).isEqualTo(OutboundMessageRepository.Status.PENDING);
        assertThat(delivery.lastError()).contains("535");
        assertThat(jdbc.queryForObject(
                "select attempts from outbound_messages where id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select next_attempt_at > now() + interval '20 seconds' from outbound_messages where id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("退避到期后再试成功：状态 SENT、attempts=2")
    void retrySucceeds() {
        channel.failWith = new ChannelSendException("超时");
        UUID id = queue.enqueue("email", "someone@test.local", "主题", "正文");
        worker.dispatchBatch();

        channel.failWith = null;
        jdbc.update("update outbound_messages set next_attempt_at = now() where id = ?", id);
        assertThat(worker.dispatchBatch()).isEqualTo(1);

        assertThat(repository.find(id).orElseThrow().status()).isEqualTo(OutboundMessageRepository.Status.SENT);
        assertThat(jdbc.queryForObject(
                "select attempts from outbound_messages where id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    @DisplayName("三次都失败：终态 FAILED，正文清空，last_error 留原因")
    void givesUpAfterThreeAttempts() {
        channel.failWith = new ChannelSendException("连接被拒绝");
        UUID id = queue.enqueue("email", "someone@test.local", "主题", "正文");

        for (int i = 0; i < 3; i++) {
            jdbc.update("update outbound_messages set next_attempt_at = now() where id = ?", id);
            worker.dispatchBatch();
        }

        var delivery = repository.find(id).orElseThrow();
        assertThat(delivery.status()).isEqualTo(OutboundMessageRepository.Status.FAILED);
        assertThat(delivery.lastError()).contains("连接被拒绝");
        assertThat(jdbc.queryForObject(
                "select body_cipher from outbound_messages where id = ?", String.class, id)).isNull();
        // 终态之后不再被领取
        assertThat(worker.dispatchBatch()).isZero();
    }

    static final class FakeChannel implements MessageChannel {
        final List<String> received = new ArrayList<>();
        ChannelSendException failWith;

        @Override
        public String channel() {
            return "email";
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public String label() {
            return "邮箱";
        }

        @Override
        public String targetHint() {
            return "you@example.com";
        }

        @Override
        public void send(String target, String subject, String body) throws ChannelSendException {
            if (failWith != null) {
                throw failWith;
            }
            received.add(body);
        }
    }
}
