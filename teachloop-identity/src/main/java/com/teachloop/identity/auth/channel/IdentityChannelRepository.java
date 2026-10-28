package com.teachloop.identity.auth.channel;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 009 T521：`identity_channels` 的读写。3 行数据，每次请求直接读库，不做缓存——
 * 「保存后下一次请求即生效」就是这么来的（AC-76.2）。
 */
@Component
public class IdentityChannelRepository {

    /** @param baseUrl 邮件通道约定为 {@code host:port}（无冒号则端口取 465） */
    public record Row(String channel, boolean enabled, String driver, String fromAddress,
                      String baseUrl, String secretCipher, String extra, Integer dailyQuota) {
    }

    private final JdbcTemplate jdbc;

    public IdentityChannelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> findAll() {
        return jdbc.query("""
                        select channel, enabled, driver, from_address, base_url, secret_cipher, extra, daily_quota
                        from identity_channels order by channel
                        """,
                (rs, i) -> new Row(rs.getString("channel"), rs.getBoolean("enabled"), rs.getString("driver"),
                        rs.getString("from_address"), rs.getString("base_url"), rs.getString("secret_cipher"),
                        rs.getString("extra"), (Integer) rs.getObject("daily_quota")));
    }

    public Optional<Row> find(String channel) {
        return findAll().stream().filter(r -> r.channel().equals(channel)).findFirst();
    }
}
