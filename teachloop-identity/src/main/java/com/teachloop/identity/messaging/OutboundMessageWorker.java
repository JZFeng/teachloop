package com.teachloop.identity.messaging;

import com.teachloop.identity.ai.SecretBox;
import com.teachloop.identity.auth.channel.ChannelSendException;
import com.teachloop.identity.auth.channel.MessageChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 2026-10-28：出站消息的派发器。
 *
 * <p>每 {@code teachloop.outbound.poll-ms}（默认 2s）领一批到期消息，交给对应的
 * {@link MessageChannel} 实现发送。失败按退避重试，3 次用完置 FAILED 并留 `last_error`。
 *
 * <p>为什么不是 `@Async`：进程重启会把在途消息丢掉，而丢一封注册激活信就等于用户进不来。
 * 队列在库里，重启后下一轮照发。
 *
 * <p>为什么在本进程而不只放 worker：现在一天几十封，app 与 worker 谁先起来谁发就行；
 * 两个进程同时跑也不会重复发——抢件是 `for update skip locked`。真到了要独立扩缩的那天，
 * 把本类挪进 worker profile 即可（表与协议不变）。
 */
@Component
public class OutboundMessageWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboundMessageWorker.class);

    /** 一轮最多领 10 条：SMTP 一封要几百毫秒到几秒，领太多会把下一轮拖住。 */
    private static final int BATCH = 10;
    /** 首投 + 两次重试；激活信 24 小时有效，5 分钟的退避不影响用户。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 第 1 次失败后 30 秒重试，第 2 次失败后 5 分钟重试。 */
    private static final Duration[] RETRY_DELAYS = {Duration.ofSeconds(30), Duration.ofMinutes(5)};
    /** 已投递的行保留 7 天（只留事实，正文早清了），之后整行删除。 */
    private static final int KEEP_DELIVERED_DAYS = 7;

    private final OutboundMessageRepository repository;
    private final SecretBox secretBox;
    private final List<MessageChannel> channels;
    private final Clock clock;
    private int purgeTicker;

    public OutboundMessageWorker(OutboundMessageRepository repository, SecretBox secretBox,
                                 List<MessageChannel> channels, Clock clock) {
        this.repository = repository;
        this.secretBox = secretBox;
        this.channels = channels;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${teachloop.outbound.poll-ms:2000}")
    public void tick() {
        try {
            dispatchBatch();
        } catch (RuntimeException ex) {
            // 一轮数据库抖动不该让调度器停摆，更不该把「意外错误」刷进日志：下一轮照常重试。
            log.warn("outbound dispatch round failed: {}", ex.getMessage());
            return;
        }
        // 清理不必每 2 秒做一次：约 200 秒跑一次足够。
        if (++purgeTicker % 100 == 0) {
            try {
                repository.purgeDelivered(KEEP_DELIVERED_DAYS);
            } catch (RuntimeException ex) {
                log.warn("outbound purge failed: {}", ex.getMessage());
            }
        }
    }

    /** 领一批并逐条投递；返回本轮实际处理的条数（测试直接调它，不依赖调度）。 */
    public int dispatchBatch() {
        List<OutboundMessageRepository.Row> due = repository.claimDue(BATCH);
        for (OutboundMessageRepository.Row row : due) {
            deliver(row);
        }
        return due.size();
    }

    private void deliver(OutboundMessageRepository.Row row) {
        MessageChannel channel = channels.stream()
                .filter(c -> c.channel().equals(row.channel()))
                .findFirst()
                .orElse(null);
        if (channel == null) {
            // 配置里留了一个没有实现的通道：重试也发不出去，直接终态。
            repository.markFailed(row.id(), "通道没有实现：" + row.channel());
            log.warn("outbound {} failed: no implementation for channel {}", row.id(), row.channel());
            return;
        }
        String body;
        try {
            body = secretBox.decrypt(row.bodyCipher());
        } catch (RuntimeException ex) {
            body = null;
        }
        if (body == null) {
            repository.markFailed(row.id(), "正文解不开（安全密钥变过？），请重发");
            log.warn("outbound {} failed: body cannot be decrypted", row.id());
            return;
        }
        try {
            channel.send(row.target(), row.subject(), body);
            repository.markSent(row.id());
        } catch (ChannelSendException | RuntimeException ex) {
            onFailure(row, ex);
        }
    }

    private void onFailure(OutboundMessageRepository.Row row, Exception ex) {
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (row.attempts() >= MAX_ATTEMPTS) {
            repository.markFailed(row.id(), reason);
            log.warn("outbound {} gave up after {} attempts: {}", row.id(), row.attempts(), reason);
            return;
        }
        Duration delay = RETRY_DELAYS[Math.min(row.attempts() - 1, RETRY_DELAYS.length - 1)];
        Instant next = Instant.now(clock).plus(delay);
        repository.markRetry(row.id(), reason, next);
        log.info("outbound {} attempt {} failed, retrying at {}: {}", row.id(), row.attempts(), next, reason);
    }
}
