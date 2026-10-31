package com.teachloop.vocab.feedback;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 管理端读取学生提交的单词反馈（`vocab_feedback`，写入口是
 * {@code POST /api/vocab/words/{id}/feedback}，见 V044 迁移）。
 *
 * <p><b>跨模块纪律</b>：`vocab_feedback` 与 `vocab_words` 都属 vocab 模块，可以 JOIN；
 * `users` 表属 identity 模块，<b>这里不碰</b>——提交人只回 `user_id`（`submitterId`），
 * 由前端展示短 id，而不是在这里越界拼邮箱/姓名。
 *
 * <p>返回 `wordForm/wordLanguage` 用 `left join`：词条后来被软删除甚至物理删除时，
 * 反馈本身仍是管理员的可见事实（不然一条反馈会凭空消失），只是词形为空。
 */
@Repository
public class FeedbackRepository {

    /** 一行反馈 + 它指向的词形（词条已不存在时 form/language 为 null）。 */
    public record FeedbackRow(long id, UUID submitterId, UUID wordId, String wordForm,
                              String wordLanguage, String note, String status, Instant createdAt) {
    }

    /**
     * `note` 的取值口径：生成列不存，写入口把备注塞进 `payload.note`（V044 的 payload 快照），
     * 而 `note` 列留给以后的原生备注。这里 `coalesce(nullif(note,''), payload->>'note')`，
     * 两条路径都读得到，且以后有人写 `note` 列时不用改查询。
     */
    private static final String SELECT = """
            select f.id, f.user_id, f.word_id, w.form, w.language,
                   coalesce(nullif(f.note, ''), f.payload ->> 'note') as note,
                   f.status, f.created_at
              from vocab_feedback f
              left join vocab_words w on w.id = f.word_id
            """;

    private final JdbcTemplate jdbc;

    public FeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** `status == null` = 不过滤（ALL）；按提交时间倒序，id 兜底保证同一毫秒内顺序稳定。 */
    public List<FeedbackRow> page(String status, int offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> arguments = new ArrayList<>();
        if (status != null) {
            sql.append(" where f.status = ?");
            arguments.add(status);
        }
        sql.append(" order by f.created_at desc, f.id desc limit ? offset ?");
        arguments.add(limit);
        arguments.add(offset);
        return jdbc.query(sql.toString(), (rs, rowNumber) -> new FeedbackRow(
                rs.getLong("id"),
                rs.getObject("user_id", UUID.class),
                rs.getObject("word_id", UUID.class),
                rs.getString("form"),
                rs.getString("language"),
                rs.getString("note"),
                rs.getString("status"),
                instant(rs.getTimestamp("created_at"))), arguments.toArray());
    }

    public long count(String status) {
        Long total = status == null
                ? jdbc.queryForObject("select count(*) from vocab_feedback", Long.class)
                : jdbc.queryForObject("select count(*) from vocab_feedback where status = ?",
                        Long.class, status);
        return total == null ? 0 : total;
    }

    public Optional<FeedbackRow> find(long id) {
        List<FeedbackRow> rows = jdbc.query(SELECT + " where f.id = ?",
                (rs, rowNumber) -> new FeedbackRow(
                        rs.getLong("id"),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("word_id", UUID.class),
                        rs.getString("form"),
                        rs.getString("language"),
                        rs.getString("note"),
                        rs.getString("status"),
                        instant(rs.getTimestamp("created_at"))), id);
        return rows.stream().findFirst();
    }

    /**
     * 改状态：只动 `vocab_feedback.status` 一列（V044 的 check 约束就是唯一状态真源），
     * 返回影响行数，调用方据此把「行不存在」翻成 404。
     */
    public int updateStatus(long id, String status) {
        return jdbc.update("update vocab_feedback set status = ? where id = ?", status, id);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
