package com.teachloop.identity.messaging;

import com.teachloop.identity.ai.SecretBox;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 2026-10-28：把「发一封（或将来的短信）」变成「排一条」。
 *
 * <p>业务方（注册、登录验证码、管理端测试邮件）只调 {@link #enqueue}：
 * 记录落库就返回，HTTP 请求不再等 SMTP 握手。真正的投递由 {@link OutboundMessageWorker} 做。
 *
 * <p>正文用 {@link SecretBox} 加密后再入库——激活链接里就是可用一次的令牌，
 * `verification_codes` 只存哈希，队列行不能成为第二份明文凭据。
 */
@Component
public class OutboundMessageQueue {

    private final OutboundMessageRepository repository;
    private final SecretBox secretBox;

    public OutboundMessageQueue(OutboundMessageRepository repository, SecretBox secretBox) {
        this.repository = repository;
        this.secretBox = secretBox;
    }

    /** 入队；返回的消息 id 可用来查投递结果（管理端测试邮件就是这么等的）。 */
    @Transactional
    public UUID enqueue(String channel, String target, String subject, String body) {
        return repository.enqueue(channel, target, subject, secretBox.encrypt(body));
    }

    public Optional<OutboundMessageRepository.Delivery> find(UUID id) {
        return repository.find(id);
    }
}
