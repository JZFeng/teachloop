package com.teachloop.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * T150：按作业 / 班级 / 学生聚合。
 * 平均分只算已提交（active 且 submitted_at 非空），未交人数单列，不混进分母。
 */
@Service
public class AggregateReportService {

    public record OfferingRow(UUID offeringId, String paperTitle, long expected, long submitted,
                              BigDecimal averageScore, long unsubmitted) {
    }

    public record ClassRow(UUID classId, String className, long offeringCount, long expected,
                           long submitted, BigDecimal averageScore, long unsubmitted) {
    }

    public record StudentRow(UUID studentId, String displayName, long expected, long submitted,
                             BigDecimal averageScore, long unsubmitted) {
    }

    private final JdbcTemplate jdbc;

    public AggregateReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OfferingRow> byOffering(List<UUID> offeringIds) {
        String in = placeholders(offeringIds.size());
        String sql = "select o.id, p.title, "
                + "(select count(distinct cm.student_id) from offering_classes oc "
                + " join class_members cm on cm.class_id = oc.class_id where oc.offering_id = o.id) expected, "
                + "(select count(*) from submissions s where s.offering_id = o.id and s.active "
                + " and s.submitted_at is not null) submitted, "
                + "(select avg(s.objective_total + s.subjective_total) from submissions s "
                + " where s.offering_id = o.id and s.active and s.submitted_at is not null) avg_score "
                + "from offerings o join papers p on p.id = o.paper_id where o.id in (" + in + ") "
                + "order by o.created_at desc";
        return jdbc.query(sql, (rs, i) -> {
            long expected = rs.getLong("expected");
            long submitted = rs.getLong("submitted");
            return new OfferingRow(uuid(rs, "id"), rs.getString("title"), expected, submitted,
                    score(rs.getBigDecimal("avg_score")), Math.max(0, expected - submitted));
        }, offeringIds.toArray());
    }

    public List<ClassRow> byClass(List<UUID> classIds) {
        if (classIds.isEmpty()) {
            return List.of();
        }
        String in = placeholders(classIds.size());
        String sql = "select c.id, c.name, "
                + "(select count(distinct oc.offering_id) from offering_classes oc where oc.class_id = c.id) offering_count, "
                + "(select count(*) from class_members cm where cm.class_id = c.id) expected, "
                + "(select count(*) from submissions s join offering_classes oc on oc.offering_id = s.offering_id "
                + " where oc.class_id = c.id and s.active and s.submitted_at is not null) submitted, "
                + "(select avg(s.objective_total + s.subjective_total) from submissions s "
                + " join offering_classes oc on oc.offering_id = s.offering_id "
                + " where oc.class_id = c.id and s.active and s.submitted_at is not null) avg_score "
                + "from classes c where c.id in (" + in + ") order by c.created_at desc";
        return jdbc.query(sql, (rs, i) -> {
            long expected = rs.getLong("expected");
            long submitted = rs.getLong("submitted");
            return new ClassRow(uuid(rs, "id"), rs.getString("name"), rs.getLong("offering_count"),
                    expected, submitted, score(rs.getBigDecimal("avg_score")),
                    Math.max(0, expected - submitted));
        }, classIds.toArray());
    }

    /** 按学生：参与场次为分母，已交场次为分子；只统计过滤范围内。 */
    public List<StudentRow> byStudent(List<UUID> offeringIds) {
        String in = placeholders(offeringIds.size());
        String sql = "select u.id, u.display_name, "
                + " (select count(distinct oc.offering_id) from offering_classes oc "
                + "  join class_members cm on cm.class_id = oc.class_id "
                + "  where cm.student_id = u.id and oc.offering_id in (" + in + ")) expected, "
                + " count(distinct s.offering_id) submitted, "
                + " avg(s.objective_total + s.subjective_total) avg_score "
                + "from users u "
                + "join class_members cm on cm.student_id = u.id "
                + "join offering_classes oc on oc.class_id = cm.class_id and oc.offering_id in (" + in + ") "
                + "left join submissions s on s.offering_id = oc.offering_id and s.student_id = u.id "
                + " and s.active and s.submitted_at is not null "
                + "group by u.id, u.display_name order by u.display_name";
        List<Object> args = new ArrayList<>();
        args.addAll(offeringIds);
        args.addAll(offeringIds);
        return jdbc.query(sql, (rs, i) -> {
            long expected = rs.getLong("expected");
            long submitted = rs.getLong("submitted");
            return new StudentRow(uuid(rs, "id"), rs.getString("display_name"), expected, submitted,
                    score(rs.getBigDecimal("avg_score")), Math.max(0, expected - submitted));
        }, args.toArray());
    }

    private static BigDecimal score(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
