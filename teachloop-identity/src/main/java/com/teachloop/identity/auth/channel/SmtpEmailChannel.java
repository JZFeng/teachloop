package com.teachloop.identity.auth.channel;

import com.teachloop.identity.ai.SecretBox;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 009 T523：SMTP 邮件通道。配置来自 {@code identity_channels}：
 * <ul>
 *   <li>{@code base_url} = {@code host:port}（缺端口按 465）</li>
 *   <li>{@code from_address} = 发件人邮箱与默认登录账号（163 等要求两者一致）</li>
 *   <li>{@code secret_cipher} = 授权码密文（{@link SecretBox} AES-GCM）</li>
 *   <li>{@code extra} = JSON：{@code {"name":"系统管理员","encryption":"ssl"}}，
 *       对应管理端的「发件人名称 / 加密方式」；旧数据没有这一列内容时
 *       name 为空（{@code From:} 只写地址）、encryption 按 ssl。</li>
 *   <li>认证账号**恒等于 from_address**（2026-10-28 用户要求：不再单列）。163/QQ 本来就要求两者一致；
 *       {@code extra.user} 即使存在也被忽略，免得留下一个改不掉的旧账号。</li>
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
    private final ObjectMapper mapper;

    public SmtpEmailChannel(IdentityChannelRepository channels, SecretBox secretBox,
                            SmtpMailSenderFactory sender, ObjectMapper mapper) {
        this.channels = channels;
        this.secretBox = secretBox;
        this.sender = sender;
        this.mapper = mapper;
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
        try {
            sender.send(settings(row, secret), target, subject, body);
        } catch (IOException ex) {
            log.warn("smtp send failed: {}", ex.getMessage());
            throw new ChannelSendException("邮件发送失败", ex);
        }
    }

    /** 行数据 + 明文授权码 → 一次发送的连接参数；extra 里的三个可选项都在这里落地。 */
    private SmtpMailSender.Settings settings(IdentityChannelRepository.Row row, String secret) {
        String[] hostPort = splitHostPort(row.baseUrl());
        JsonNode extra = parseExtra(row.extra());
        int port = port(hostPort[1]);
        return new SmtpMailSender.Settings(hostPort[0], port, row.fromAddress(), secret, row.fromAddress(),
                text(extra, "name"), security(text(extra, "encryption")));
    }

    /**
     * 加密方式：显式配置优先；没配过的老数据按 ssl（本类此前的唯一行为），
     * 这样升级部署不会把原本能发的 465 通道改坏。
     */
    static SmtpMailSender.Security security(String value) {
        if (value == null) {
            return SmtpMailSender.Security.SSL;
        }
        return switch (value.trim().toLowerCase()) {
            case "starttls" -> SmtpMailSender.Security.STARTTLS;
            case "none" -> SmtpMailSender.Security.NONE;
            default -> SmtpMailSender.Security.SSL;
        };
    }

    private JsonNode parseExtra(String extra) {
        if (extra == null || extra.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(extra);
            return node != null && node.isObject() ? node : null;
        } catch (RuntimeException ex) {
            log.warn("identity channel extra is not valid JSON; falling back to defaults");
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.isString() ? value.asString() : value.toString();
        return text.isBlank() ? null : text.trim();
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

    private static int port(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_PORT;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
