package com.teachloop.report.service.export;

import com.teachloop.report.service.export.ExportModels.ClassStat;
import com.teachloop.report.service.export.ExportModels.MissingStudent;
import com.teachloop.report.service.export.ExportModels.OfferingMeta;
import com.teachloop.report.service.export.ExportModels.StudentSubmission;
import com.teachloop.report.service.export.ExportModels.WrongItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 导出所需的只读 SQL（T120 / T121 / T124）。
 *
 * <p>本类复用 plan §3.2 允许的「跨模块只读聚合」豁免：直读 classroom 的 classes /
 * class_members、identity 的 users、paper 的 papers / objective_items、sitting 的
 * offerings / offering_classes / submissions / objective_answers、grading 的 reviews。
 * 只 select，不写任何表，也不 import 任何模块的内部类型。
 *
 * <p>数字口径与 {@code OfferingEndpoint#classSummary} 对齐：
 * <ul>
 *   <li>每个 (offering, student) 先取 attempt_no 最大的一次提交，再按「已交卷且未拒收」计入实交；</li>
 *   <li>按时提交率 = 按时份数 / 实交份数 × 100，先四舍五入到 4 位再乘 100、最后保留 2 位；</li>
 *   <li>平均分 = 实交总分之和 / 实交份数，保留 2 位，且只在 publish_scores=true 时给值；</li>
 *   <li>未交学生在平均分与实交里都不出现，单独走 {@link #missingStudents(UUID)}。</li>
 * </ul>
 */
@Service
public class ExportReadService {

    /** D-14：独立作业没有 papers 行，必须 left join 并从快照回退标题，否则整场导出会消失。 */
    private static final String OFFERING_SQL = """
            select o.id, o.kind, o.due_at, o.publish_scores,
                   coalesce(p.title, o.paper_snapshot->>'title') as paper_title
            from offerings o
            left join papers p on p.id = o.paper_id
            where o.id = ?
            """;

    private static final String CLASS_STATS_SQL = """
            with members as (
                select oc.class_id, cm.student_id
                from offering_classes oc
                join class_members cm on cm.class_id = oc.class_id
                where oc.offering_id = ?
            ),
            ranked as (
                select m.class_id, m.student_id,
                       s.id, s.submitted_at, s.rejected_at, s.late_by_seconds,
                       s.objective_total, s.subjective_total,
                       row_number() over (partition by m.class_id, m.student_id
                                          order by s.attempt_no desc, s.created_at desc, s.id desc) as rn
                from members m
                left join submissions s
                       on s.offering_id = ?
                      and s.student_id = m.student_id
            ),
            stats as (
                select m.class_id,
                       count(*) as expected,
                       count(r.id) filter (
                           where r.rn = 1
                             and r.submitted_at is not null
                             and r.rejected_at is null) as submitted,
                       count(r.id) filter (
                           where r.rn = 1
                             and r.submitted_at is not null
                             and r.rejected_at is null
                             and r.late_by_seconds = 0) as on_time,
                       coalesce(sum(case
                               when r.rn = 1
                                and r.submitted_at is not null
                                and r.rejected_at is null
                               then r.objective_total + r.subjective_total
                               else 0 end), 0) as score_sum
                from members m
                left join ranked r on r.class_id = m.class_id and r.student_id = m.student_id
                group by m.class_id
            ),
            rejected as (
                select oc.class_id, count(*) as rejected
                from offering_classes oc
                join class_members cm on cm.class_id = oc.class_id
                join submissions s
                     on s.offering_id = oc.offering_id
                    and s.student_id = cm.student_id
                where oc.offering_id = ?
                  and s.rejected_at is not null
                group by oc.class_id
            )
            select c.id as class_id,
                   c.name as class_name,
                   st.expected,
                   st.submitted,
                   st.on_time,
                   coalesce(rj.rejected, 0) as rejected,
                   st.score_sum
            from offering_classes oc
            join classes c on c.id = oc.class_id
            join stats st on st.class_id = oc.class_id
            left join rejected rj on rj.class_id = oc.class_id
            where oc.offering_id = ?
            order by c.name asc, c.id asc
            """;

    private static final String LATEST_SUBMITTED_SQL = """
            with members as (
                select distinct cm.student_id
                from offering_classes oc
                join class_members cm on cm.class_id = oc.class_id
                where oc.offering_id = ?
            ),
            latest as (
                select distinct on (s.student_id)
                       s.id as submission_id,
                       s.student_id,
                       s.attempt_no,
                       s.submitted_at,
                       s.late_by_seconds,
                       s.grading_state,
                       s.auto_submitted,
                       s.objective_total,
                       s.subjective_total
                from submissions s
                join members m on m.student_id = s.student_id
                where s.offering_id = ?
                  and s.submitted_at is not null
                  and s.rejected_at is null
                order by s.student_id, s.attempt_no desc, s.submitted_at desc, s.id desc
            )
            select l.submission_id, l.student_id, u.display_name, l.attempt_no, l.submitted_at,
                   l.late_by_seconds, l.grading_state, l.auto_submitted,
                   l.objective_total, l.subjective_total
            from latest l
            join users u on u.id = l.student_id
            order by u.display_name asc, l.student_id asc
            """;

    private static final String MISSING_SQL = """
            with members as (
                select distinct cm.student_id
                from offering_classes oc
                join class_members cm on cm.class_id = oc.class_id
                where oc.offering_id = ?
            )
            select m.student_id, u.display_name
            from members m
            join users u on u.id = m.student_id
            where not exists (
                select 1
                from submissions s
                where s.offering_id = ?
                  and s.student_id = m.student_id
                  and s.submitted_at is not null
                  and s.rejected_at is null)
            order by u.display_name asc, m.student_id asc
            """;

    private static final String WRONG_ITEMS_SQL = """
            select oa.submission_id,
                   i.number,
                   i.question_type,
                   i.stem,
                   oa.answer,
                   i.answer_key,
                   i.points
            from objective_answers oa
            join objective_items i on i.id = oa.item_id
            where oa.submission_id = any(?::uuid[])
              and oa.correct = false
              and i.question_type <> 'ESSAY'
            order by oa.submission_id, i.number asc
            """;

    private static final String REVIEW_COMMENTS_SQL = """
            select submission_id, comment
            from reviews
            where submission_id = any(?::uuid[])
              and comment is not null
              and btrim(comment) <> ''
            """;

    private final JdbcTemplate jdbc;

    public ExportReadService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 找不到布置时返回空，由调用方转 404。 */
    public Optional<OfferingMeta> offering(UUID offeringId) {
        return jdbc.query(OFFERING_SQL, ps -> ps.setObject(1, offeringId), rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new OfferingMeta(
                    rs.getObject("id", UUID.class),
                    rs.getString("paper_title"),
                    rs.getString("kind"),
                    instant(rs, "due_at"),
                    rs.getBoolean("publish_scores")));
        });
    }

    /**
     * 按班统计。{@code publishScores} 只决定平均分是否给值，不影响计数。
     * 与 {@code classSummary} 使用同一套 BigDecimal 运算，保证数字逐位一致。
     */
    public List<ClassStat> classStats(UUID offeringId, boolean publishScores) {
        return jdbc.query(CLASS_STATS_SQL, ps -> {
            ps.setObject(1, offeringId);
            ps.setObject(2, offeringId);
            ps.setObject(3, offeringId);
            ps.setObject(4, offeringId);
        }, (ResultSet rs) -> {
            List<ClassStat> out = new ArrayList<>();
            while (rs.next()) {
                long submitted = rs.getLong("submitted");
                long onTime = rs.getLong("on_time");
                long rejected = rs.getLong("rejected");
                BigDecimal scoreSum = rs.getBigDecimal("score_sum");
                BigDecimal rate = submitted == 0 ? null
                        : BigDecimal.valueOf(onTime)
                                .divide(BigDecimal.valueOf(submitted), 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP);
                BigDecimal average = null;
                if (publishScores && submitted > 0) {
                    average = scoreSum.divide(BigDecimal.valueOf(submitted), 2, RoundingMode.HALF_UP);
                }
                out.add(new ClassStat(
                        rs.getObject("class_id", UUID.class).toString(),
                        rs.getString("class_name"),
                        rs.getInt("expected"),
                        submitted,
                        onTime,
                        submitted - onTime,
                        rejected,
                        rate,
                        average));
            }
            return out;
        });
    }

    /** 每位学生最新一次「已交卷且未拒收」的 attempt，已交卷学生才出现。 */
    public List<StudentSubmission> latestSubmitted(UUID offeringId) {
        return jdbc.query(LATEST_SUBMITTED_SQL, ps -> {
            ps.setObject(1, offeringId);
            ps.setObject(2, offeringId);
        }, (ResultSet rs) -> {
            List<StudentSubmission> out = new ArrayList<>();
            while (rs.next()) {
                BigDecimal objective = rs.getBigDecimal("objective_total");
                BigDecimal subjective = rs.getBigDecimal("subjective_total");
                out.add(new StudentSubmission(
                        rs.getObject("submission_id", UUID.class),
                        rs.getObject("student_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getInt("attempt_no"),
                        instant(rs, "submitted_at"),
                        rs.getLong("late_by_seconds"),
                        rs.getString("grading_state"),
                        rs.getBoolean("auto_submitted"),
                        objective,
                        subjective,
                        objective.add(subjective)));
            }
            return out;
        });
    }

    /** 参与班级里没有任何有效提交的学生（拒绝与草稿都不算提交）。 */
    public List<MissingStudent> missingStudents(UUID offeringId) {
        return jdbc.query(MISSING_SQL, ps -> {
            ps.setObject(1, offeringId);
            ps.setObject(2, offeringId);
        }, (ResultSet rs) -> {
            List<MissingStudent> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new MissingStudent(
                        rs.getObject("student_id", UUID.class),
                        rs.getString("display_name")));
            }
            return out;
        });
    }

    /** 按 submission 分组的错题；空列表直接返回空 Map，避免拼出空 uuid 数组查询。 */
    public Map<UUID, List<WrongItem>> wrongItems(List<UUID> submissionIds) {
        if (submissionIds.isEmpty()) {
            return Map.of();
        }
        UUID[] ids = submissionIds.toArray(UUID[]::new);
        return jdbc.query(WRONG_ITEMS_SQL, ps -> setUuidArray(ps, 1, ids), (ResultSet rs) -> {
            Map<UUID, List<WrongItem>> out = new LinkedHashMap<>();
            while (rs.next()) {
                out.computeIfAbsent(rs.getObject("submission_id", UUID.class), k -> new ArrayList<>())
                        .add(new WrongItem(
                                rs.getInt("number"),
                                rs.getString("question_type"),
                                rs.getString("stem"),
                                rs.getString("answer"),
                                rs.getString("answer_key"),
                                rs.getBigDecimal("points")));
            }
            return out;
        });
    }

    /** 复核评语：与 my-feedback 一致，只要有非空 comment 就带出，不按复核状态过滤。 */
    public Map<UUID, String> reviewComments(List<UUID> submissionIds) {
        if (submissionIds.isEmpty()) {
            return Map.of();
        }
        UUID[] ids = submissionIds.toArray(UUID[]::new);
        return jdbc.query(REVIEW_COMMENTS_SQL, ps -> setUuidArray(ps, 1, ids), (ResultSet rs) -> {
            Map<UUID, String> out = new LinkedHashMap<>();
            while (rs.next()) {
                out.put(rs.getObject("submission_id", UUID.class), rs.getString("comment"));
            }
            return out;
        });
    }

    private static void setUuidArray(PreparedStatement ps, int index, UUID[] values) throws SQLException {
        ps.setArray(index, ps.getConnection().createArrayOf("uuid", values));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
