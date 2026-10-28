package com.teachloop.identity.auth.channel;

import com.teachloop.identity.ai.SecretBox;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 009 T523：SMTP 邮件通道。配置来自 {@code identity_channels}：
 * <ul>
 *   <li>{@code base_url} = {@code host:port}（缺端口按 465）</li>
 *   <li>{@code from_address} = 发件人与登录账号（163 等要求两者一致）</li>
 *   <li>{@code secret_cipher} = 授权码密文（{@link SecretBox} AES-GCM）</li>
 * </ul>
 *
 * <p>密钥解不开时 {@link #configured()} 为 false——通道按「未配置」处理，不抛 500（AC-76.4）。
 */
@Component
public class SmtpEmailChannel implements VerificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailChannel.class);
    private static final int DEFAULT_PORT = 465;

    private final IdentityChannelRepository channels;
    private final SecretBox secretBox;
    private final SmtpMailSenderFactory sender;

    public SmtpEmailChannel(IdentityChannelRepository channels, SecretBox secretBox,
                            SmtpMailSenderFactory sender) {
        this.channels = channels;
        this.secretBox = secretBox;
        this.sender = sender;
    }

    @Override
    public String channel() {
        return "email";
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
    public boolean configured() {
        return channels.find("email")
                .map(r -> notBlank(r.fromAddress()) && notBlank(r.baseUrl()) && notBlank(r.secretCipher())
                        && decrypt(r.secretCipher()) != null)
                .orElse(false);
    }

    @Override
    public void send(String target, String subject, String body) throws ChannelSendException {
        IdentityChannelRepository.Row row = channels.find("email")
                .orElseThrow(() -> new ChannelSendException("邮件通道未配置"));
        String secret = decrypt(row.secretCipher());
        if (secret == null) {
            throw new ChannelSendException("邮件通道密钥不可用");
        }
        String[] hostPort = splitHostPort(row.baseUrl());
        try {
            sender.send(hostPort[0], Integer.parseInt(hostPort[1]),
                    username(row), secret, row.fromAddress(), target, subject, body);
        } catch (IOException ex) {
            log.warn("smtp send failed: {}", ex.getMessage());
            throw new ChannelSendException("邮件发送失败", ex);
        }
    }

    /** 163/QQ 的登录账号就是发件地址；显式允许 extra 里用 {@code user=} 覆盖。 */
    private static String username(IdentityChannelRepository.Row row) {
        String extra = row.extra();
        if (extra != null && extra.contains("\"user\"")) {
            int i = extra.indexOf("\"user\"");
            int start = extra.indexOf('"', extra.indexOf(':', i) + 1);
            int end = extra.indexOf('"', start + 1);
            if (start > 0 && end > start) {
                return extra.substring(start + 1, end);
            }
        }
        return row.fromAddress();
    }

    private String decrypt(String cipher) {
        try {
            return secretBox.decrypt(cipher);
        } catch (RuntimeException ex) {
            log.warn("identity channel secret cannot be decrypted (security secret changed?)");
            return null;
        }
    }

    private static String[] splitHostPort(String baseUrl) {
        String v = baseUrl.trim();
        int idx = v.lastIndexOf(':');
        if (idx > 0) {
            return new String[]{v.substring(0, idx), v.substring(idx + 1)};
        }
        return new String[]{v, String.valueOf(DEFAULT_PORT)};
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
