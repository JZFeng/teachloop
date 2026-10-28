package com.teachloop.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 009 T522：验证码与激活令牌的生命周期。
 *
 * <p>两类凭据共用 {@code verification_codes}：
 * <ul>
 *   <li>6 位数字码（登录 / 忘记密码），10 分钟，错 5 次作废；</li>
 *   <li>32 字节令牌（注册激活），24 小时，一次性。</li>
 * </ul>
 * 只存 {@code sha256(raw|target)}，库被读走也拿不到可用凭据。
 */
@Service
public class CodeService {

    /** 错满这么多次，该条凭据立即作废（AC-73.5）。 */
    public static final int MAX_ATTEMPTS = 5;

    public record Issued(UUID id, String raw, Instant expiresAt) {
    }

    public enum ConsumeStatus { OK, INVALID, EXPIRED, USED, ATTEMPTS_EXCEEDED, NOT_FOUND }

    public record ConsumeResult(ConsumeStatus status, UUID id) {
    }

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public CodeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 发一条数字验证码（默认 6 位）。 */
    @Transactional
    public Issued issueCode(VerificationPurpose purpose, String channel, String target, Duration ttl) {
        return issue(purpose, channel, target, ttl, numeric(6));
    }

    /** 发一条一次性的激活令牌。 */
    @Transactional
    public Issued issueToken(VerificationPurpose purpose, String channel, String target, Duration ttl) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(ttl);
        // 令牌的哈希不掺 target：激活页只拿到 token，要能反查到这条记录。
        jdbc.update("""
                        insert into verification_codes (id, purpose, channel, target, code_hash, expires_at, attempts)
                        values (?, ?, ?, ?, ?, ?, 0)
                        """,
                id, purpose.name(), channel, normalize(target), hash(raw, ""), java.sql.Timestamp.from(expiresAt));
        return new Issued(id, raw, expiresAt);
    }

    private Issued issue(VerificationPurpose purpose, String channel, String target, Duration ttl, String raw) {
        UUID id = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(ttl);
        jdbc.update("""
                        insert into verification_codes (id, purpose, channel, target, code_hash, expires_at, attempts)
                        values (?, ?, ?, ?, ?, ?, 0)
                        """,
                id, purpose.name(), channel, normalize(target), hash(raw, target),
                java.sql.Timestamp.from(expiresAt));
        return new Issued(id, raw, expiresAt);
    }

    /**
     * 校验并消费。**状态判定顺序**：已用 → 已过期 → 次数超限 → 不匹配 → 通过。
     * 这样用户看到的原因永远是「最该先处理的那个」。
     */
    @Transactional
    public ConsumeResult consume(VerificationPurpose purpose, String channel, String target, String raw) {
        Optional<Row> found = latest(purpose, channel, target);
        if (found.isEmpty()) {
            return new ConsumeResult(ConsumeStatus.NOT_FOUND, null);
        }
        Row row = found.get();
        if (row.consumedAt() != null) {
            return new ConsumeResult(ConsumeStatus.USED, row.id());
        }
        if (row.expiresAt().isBefore(Instant.now())) {
            return new ConsumeResult(ConsumeStatus.EXPIRED, row.id());
        }
        if (row.attempts() >= MAX_ATTEMPTS) {
            return new ConsumeResult(ConsumeStatus.ATTEMPTS_EXCEEDED, row.id());
        }
        if (!hash(raw, target).equals(row.codeHash())) {
            int attempts = row.attempts() + 1;
            jdbc.update("update verification_codes set attempts = ? where id = ?", attempts, row.id());
            return attempts >= MAX_ATTEMPTS
                    ? new ConsumeResult(ConsumeStatus.ATTEMPTS_EXCEEDED, row.id())
                    : new ConsumeResult(ConsumeStatus.INVALID, row.id());
        }
        // 条件更新保证并发下只有一个成功
        int updated = jdbc.update(
                "update verification_codes set consumed_at = now() where id = ? and consumed_at is null", row.id());
        return updated == 1
                ? new ConsumeResult(ConsumeStatus.OK, row.id())
                : new ConsumeResult(ConsumeStatus.USED, row.id());
    }

    /** 只看状态，不消费——激活页 GET 必须无副作用（AC-73.4）。 */
    @Transactional(readOnly = true)
    public TokenState inspectToken(VerificationPurpose purpose, String raw) {
        Optional<Row> found = byTokenHash(purpose, hash(raw, ""));
        if (found.isEmpty()) {
            return new TokenState(ConsumeStatus.NOT_FOUND, null, null);
        }
        Row row = found.get();
        ConsumeStatus status = row.consumedAt() != null ? ConsumeStatus.USED
                : row.expiresAt().isBefore(Instant.now()) ? ConsumeStatus.EXPIRED
                : ConsumeStatus.OK;
        return new TokenState(status, row.target(), row.id());
    }

    /** 消费令牌（激活用的 POST）。 */
    @Transactional
    public ConsumeResult consumeToken(VerificationPurpose purpose, String raw) {
        Optional<Row> found = byTokenHash(purpose, hash(raw, ""));
        if (found.isEmpty()) {
            return new ConsumeResult(ConsumeStatus.NOT_FOUND, null);
        }
        Row row = found.get();
        if (row.consumedAt() != null) {
            return new ConsumeResult(ConsumeStatus.USED, row.id());
        }
        if (row.expiresAt().isBefore(Instant.now())) {
            return new ConsumeResult(ConsumeStatus.EXPIRED, row.id());
        }
        int updated = jdbc.update(
                "update verification_codes set consumed_at = now() where id = ? and consumed_at is null", row.id());
        return updated == 1
                ? new ConsumeResult(ConsumeStatus.OK, row.id())
                : new ConsumeResult(ConsumeStatus.USED, row.id());
    }

    public record TokenState(ConsumeStatus status, String target, UUID id) {
    }

    private Optional<Row> byTokenHash(VerificationPurpose purpose, String codeHash) {
        List<Row> rows = jdbc.query("""
                        select id, consumed_at, expires_at, attempts, code_hash, target
                        from verification_codes
                        where purpose = ? and code_hash = ?
                        order by created_at desc limit 1
                        """,
                (rs, i) -> new Row(rs.getObject("id", UUID.class),
                        rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getInt("attempts"),
                        rs.getString("code_hash"),
                        rs.getString("target")),
                purpose.name(), codeHash);
        return rows.stream().findFirst();
    }

    /** 最近一次签发时间，用于「同一邮箱 60 秒冷却」。 */
    @Transactional(readOnly = true)
    public Optional<Instant> lastIssuedAt(VerificationPurpose purpose, String target) {
        List<Instant> rows = jdbc.query(
                "select max(created_at) as last_at from verification_codes where purpose = ? and target = ?",
                (rs, i) -> rs.getTimestamp("last_at") == null ? null : rs.getTimestamp("last_at").toInstant(),
                purpose.name(), normalize(target));
        // 没有记录时 max() 返回 null，上面那条 mapper 会给出 null，必须在这里兜住（否则 findFirst() NPE）
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** 发送失败时把刚发的这条作废，避免用户拿到一条永远用不了的码还占着频控额度。 */
    @Transactional
    public void invalidate(UUID id) {
        jdbc.update("update verification_codes set consumed_at = now() where id = ? and consumed_at is null", id);
    }

    /** 清理过期超过保留期的行（幂等，供定时任务调用）。 */
    @Transactional
    public int deleteExpiredBefore(Instant before) {
        return jdbc.update("delete from verification_codes where expires_at < ?", java.sql.Timestamp.from(before));
    }

    private Optional<Row> latest(VerificationPurpose purpose, String channel, String target) {
        List<Row> rows = jdbc.query("""
                        select id, consumed_at, expires_at, attempts, code_hash, target
                        from verification_codes
                        where purpose = ? and channel = ? and target = ?
                        order by created_at desc limit 1
                        """,
                (rs, i) -> new Row(rs.getObject("id", UUID.class),
                        rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getInt("attempts"),
                        rs.getString("code_hash"),
                        rs.getString("target")),
                purpose.name(), channel, normalize(target));
        return rows.stream().findFirst();
    }

    private record Row(UUID id, Instant consumedAt, Instant expiresAt, int attempts, String codeHash,
                       String target) {
    }

    private static String normalize(String target) {
        return target == null ? "" : target.trim().toLowerCase();
    }

    private static String numeric(int digits) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            sb.append(r.nextInt(10));
        }
        return sb.toString();
    }

    /** sha256(raw|normalizedTarget)：同一验证码在不同目标下哈希不同，避免彩虹表。 */
    private static String hash(String raw, String target) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((raw + "|" + normalize(target)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("无法计算验证码哈希", ex);
        }
    }
}
