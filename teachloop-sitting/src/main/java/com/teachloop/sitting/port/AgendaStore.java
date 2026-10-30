package com.teachloop.sitting.port;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 011 / T558：今日页作业与考试片段的 SQL 唯一入口（plan §4.1）。
 *
 * <p>只碰 sitting 自己的三张表（{@code offerings} / {@code offering_classes} / {@code submissions}）；
 * 班名不在这里 join（{@code classes} 属 classroom），由 {@link AgendaAdapter} 走
 * {@code Ports.ClassMembership.classNames} 批量补。这条不是洁癖：
 * {@code check-boundaries} 的 {@code check_module_isolation} 会拦跨模块 SQL。
 *
 * <p>标题在 {@code offerings.paper_snapshot} 的 JSON 里（表没有 title 列），
 * 用 {@code ::jsonb ->> 'title'} 只取一个字段，避免把整份快照读进内存。
 */
@Component
public class AgendaStore {

    /** 待交作业的一行（尚未按 offering 去重，也还没有班名）。 */
    public record PendingRow(UUID offeringId, String title, UUID classId, Instant dueAt, String status) {
    }

    /** 未来考试的一行。 */
    public record ExamRow(UUID offeringId, String title, UUID classId, Instant startsAt) {
    }

    private final JdbcTemplate jdbc;

    public AgendaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 学生的待交作业：从「我所在的班」出发的一次查询，不读全部已发布作业再逐个判断。
     *
     * <p>只要待交（未交、草稿未交、被拒收）；已经交了的作业不出现在「待交」卡里。
     * 同一场布置发到多个班时会有多行，去重与「取最早截止」在 {@link AgendaAdapter}。
     */
    public List<PendingRow> pending(Collection<UUID> classIds, UUID studentId) {
        UUID[] classes = classIds.toArray(UUID[]::new);
        return jdbc.query("""
                select o.id,
                       coalesce(o.paper_snapshot::jsonb ->> 'title', '') as title,
                       oc.class_id,
                       coalesce(oc.due_at_override, o.due_at) as due_at,
                       case when s.id is null or s.submitted_at is null or s.rejected_at is not null
                            then 'todo' else 'submitted' end as status
                  from offering_classes oc
                  join offerings o on o.id = oc.offering_id
                  left join submissions s
                         on s.offering_id = o.id and s.student_id = ? and s.active
                 where oc.class_id = any(?::uuid[])
                   and o.status = 'PUBLISHED'
                   and o.kind = 'ASSIGNMENT'
                   and (s.id is null or s.submitted_at is null or s.rejected_at is not null)
                 order by due_at asc, o.id asc
                """, ps -> {
            ps.setObject(1, studentId);
            setUuidArray(ps, 2, classes);
        }, (rs, rowNumber) -> new PendingRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getObject(3, UUID.class),
                rs.getTimestamp(4).toInstant(),
                rs.getString(5)));
    }

    /**
     * 未来 [from, to] 内开始的考试。已交卷与否不影响「即将开始」：卡片的语义是提醒，
     * 不是待办（AC-90.4 只给时间与条数约束）。
     */
    public List<ExamRow> upcoming(Collection<UUID> classIds, Instant from, Instant to) {
        UUID[] classes = classIds.toArray(UUID[]::new);
        return jdbc.query("""
                select o.id,
                       coalesce(o.paper_snapshot::jsonb ->> 'title', '') as title,
                       oc.class_id,
                       o.start_at
                  from offering_classes oc
                  join offerings o on o.id = oc.offering_id
                 where oc.class_id = any(?::uuid[])
                   and o.status = 'PUBLISHED'
                   and o.kind = 'EXAM'
                   and o.start_at is not null
                   and o.start_at >= ?
                   and o.start_at <= ?
                 order by o.start_at asc, o.id asc
                """, ps -> {
            setUuidArray(ps, 1, classes);
            ps.setObject(2, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
            ps.setObject(3, OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        }, (rs, rowNumber) -> new ExamRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getObject(3, UUID.class),
                rs.getTimestamp(4).toInstant()));
    }

    private static void setUuidArray(PreparedStatement ps, int index, UUID[] values) throws SQLException {
        Array array = ps.getConnection().createArrayOf("uuid", values);
        ps.setArray(index, array);
    }
}
