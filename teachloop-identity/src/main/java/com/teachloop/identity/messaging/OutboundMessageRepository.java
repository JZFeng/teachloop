package com.teachloop.identity.messaging;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 2026-10-28：出站消息队列的读写（`outbound_messages`，见 V074）。
 *
 * <p>抢件用 `for update skip locked`：多个进程（app + worker）同时扫也不会重复发信，
 * 这是本项目其他 worker（批改、词汇生成）一致的取件方式。
 *
 * <p>正文是密文：{@link #enqueue} 之前由调用方加密，{@link #markSent} / {@link #markFailed}
 * 会把 `body_cipher` 清空——队列行不该长期留着可用一次的激活链接。
 */
@Component
public class OutboundMessageRepository {

    /** 投递状态；字符串入库，避免 enum 顺序变化影响历史行。 */
    public enum Status { PENDING, SENT, FAILED }

    public record Row(UUID id, String channel, String target, String subject, String bodyCipher, int attempts) {
    }

    private final JdbcTemplate jdbc;

    public OutboundMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID enqueue(String channel, String target, String subject, String bodyCipher) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        insert into outbound_messages (id, channel, target, subject, body_cipher)
                        values (?, ?, ?, ?, ?)
                        """,
                id, channel, target, subject, bodyCipher);
        return id;
    }

    /**
     * 领一批到期的 PENDING，并把 attempts +1（这一次算已经用掉了）。
     *
     * <p>单条 SQL 完成「挑 + 锁 + 记账」，避免先查后更新之间被另一个进程抢走。
     */
    public List<Row> claimDue(int limit) {
        return jdbc.query("""
                        update outbound_messages m
                           set attempts = m.attempts + 1, updated_at = now()
                          from (
                                select id from outbound_messages
                                 where status = 'PENDING' and next_attempt_at <= now()
                                 order by next_attempt_at
                                 limit ?
                                   for update skip locked
                               ) due
                         where m.id = due.id
                        returning m.id, m.channel, m.target, m.subject, m.body_cipher, m.attempts
                        """,
                (rs, i) -> row(rs), limit);
    }

    public void markSent(UUID id) {
        jdbc.update("""
                        update outbound_messages
                           set status = 'SENT', body_cipher = null, last_error = null,
                               sent_at = now(), updated_at = now()
                         where id = ?
                        """, id);
    }

    /** 还可以再试：留在 PENDING，把下次时间推到未来。 */
    public void markRetry(UUID id, String error, Instant nextAttemptAt) {
        jdbc.update("""
                        update outbound_messages
                           set status = 'PENDING', last_error = ?, next_attempt_at = ?, updated_at = now()
                         where id = ?
                        """, truncate(error), java.sql.Timestamp.from(nextAttemptAt), id);
    }

    /** 重试用完：终态 FAILED，正文清掉。 */
    public void markFailed(UUID id, String error) {
        jdbc.update("""
                        update outbound_messages
                           set status = 'FAILED', body_cipher = null, last_error = ?, updated_at = now()
                         where id = ?
                        """, truncate(error), id);
    }

    public record Delivery(UUID id, String channel, String target, Status status, String lastError, Instant sentAt) {
    }

    public Optional<Delivery> find(UUID id) {
        return jdbc.query("""
                        select id, channel, target, status, last_error, sent_at
                          from outbound_messages where id = ?
                        """,
                (rs, i) -> new Delivery(rs.getObject("id", UUID.class), rs.getString("channel"),
                        rs.getString("target"), Status.valueOf(rs.getString("status")),
                        rs.getString("last_error"),
                        rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant()),
                id).stream().findFirst();
    }

    /** 已投递的行只留事实，正文已在 markSent 里清掉；超过保留期就整行删掉。 */
    public int purgeDelivered(int keepDays) {
        return jdbc.update("""
                        delete from outbound_messages
                         where status = 'SENT' and sent_at < now() - make_interval(days => ?)
                        """, keepDays);
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("channel"), rs.getString("target"),
                rs.getString("subject"), rs.getString("body_cipher"), rs.getInt("attempts"));
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
