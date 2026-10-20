package com.teachloop.report.service;

import com.teachloop.report.api.ReportDtos;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 常见错误 Top N 的只读聚合（T152 / T192，AC-17.9–17.12）。
 *
 * <p>本类是 plan §3.2 允许的**跨模块只读聚合**唯一豁免：一条 native SQL 直接读
 * sitting 的 submissions / objective_answers 与 paper 的 objective_items，
 * 不引入任何模块的 @Entity 或 repository，也不写任何表。
 *
 * <p>口径：
 * <ul>
 *   <li>每个 (offering_id, student_id) 只取 attempt_no 最大的一次，且必须已交卷、未被拒收；</li>
 *   <li>正确性只读 {@code objective_answers.correct}（V012 列），不在 SQL 里重算匹配策略；</li>
 *   <li>只统计 question_type != 'ESSAY' 的题；</li>
 *   <li>排序与 limit 只影响返回，不对聚合范围做任何上限（AC-17.10）。</li>
 * </ul>
 */
@Service
public class WrongItemsReportService {

    /** 未给 to 时的上界：远晚于任何真实提交时间，仍落在 timestamptz 可表示范围内。 */
    private static final Instant NO_UPPER_BOUND = Instant.parse("9999-12-31T23:59:59Z");

    private static final String SQL = """
            with target_offerings as (
                select o.id
                from offerings o
                where o.id = any(?::uuid[])
                  and o.status = 'PUBLISHED'
                  and (cardinality(?::uuid[]) = 0
                       or exists (select 1
                                  from offering_classes oc
                                  where oc.offering_id = o.id
                                    and oc.class_id = any(?::uuid[])))
            ),
            latest as (
                select distinct on (s.offering_id, s.student_id)
                       s.id, s.offering_id
                from submissions s
                join target_offerings t on t.id = s.offering_id
                where s.submitted_at is not null
                  and s.rejected_at is null
                  and s.submitted_at >= ?
                  and s.submitted_at <= ?
                order by s.offering_id, s.student_id, s.attempt_no desc, s.submitted_at desc, s.id desc
            ),
            answers as (
                select i.id as item_id, i.number, i.stem, i.question_type, i.points,
                       oa.answer, oa.correct
                from objective_answers oa
                join latest l on l.id = oa.submission_id
                join objective_items i on i.id = oa.item_id
                where i.question_type <> 'ESSAY'
            ),
            item_stats as (
                select item_id, number, stem, question_type, points,
                       count(*) as answered,
                       count(*) filter (where not correct) as wrong
                from answers
                group by item_id, number, stem, question_type, points
            ),
            ranked_wrong as (
                select item_id, answer, count(*) as picked,
                       row_number() over (partition by item_id
                                          order by count(*) desc, answer asc) as rn
                from answers
                where not correct
                group by item_id, answer
            ),
            top_wrong as (
                select item_id, answer as top_wrong_answer, picked as top_wrong_count
                from ranked_wrong
                where rn = 1
            )
            select st.item_id,
                   st.number,
                   st.stem,
                   st.question_type,
                   st.points,
                   st.answered,
                   st.wrong,
                   round((st.answered - st.wrong) * 100.0 / st.answered, 1) as correct_rate,
                   tw.top_wrong_answer,
                   coalesce(tw.top_wrong_count, 0) as top_wrong_count,
                   count(*) over () as total_items,
                   (select count(distinct offering_id) from latest) as offering_count
            from item_stats st
            left join top_wrong tw on tw.item_id = st.item_id
            order by correct_rate asc, st.answered desc, st.number asc
            limit ?
            """;

    private final JdbcTemplate jdbc;

    public WrongItemsReportService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * @param offeringIds 必填，已去重的 UUID 列表
     * @param classIds    可空/空列表表示不按班级筛选
     * @param from        可空，按 submissions.submitted_at 下界（含）
     * @param to          可空，按 submissions.submitted_at 上界（含）
     * @param limit       返回条数上限 1–100
     */
    public ReportDtos.WrongItemsView query(List<UUID> offeringIds, List<UUID> classIds,
                                           Instant from, Instant to, int limit) {
        UUID[] offeringArray = offeringIds.toArray(UUID[]::new);
        UUID[] classArray = classIds == null ? new UUID[0] : classIds.toArray(UUID[]::new);
        Instant fromBound = from == null ? Instant.EPOCH : from;
        Instant toBound = to == null ? NO_UPPER_BOUND : to;

        return jdbc.query(SQL, ps -> {
            setUuidArray(ps, 1, offeringArray);
            setUuidArray(ps, 2, classArray);
            setUuidArray(ps, 3, classArray);
            ps.setObject(4, OffsetDateTime.ofInstant(fromBound, ZoneOffset.UTC));
            ps.setObject(5, OffsetDateTime.ofInstant(toBound, ZoneOffset.UTC));
            ps.setInt(6, limit);
        }, WrongItemsReportService::extract);
    }

    private static void setUuidArray(PreparedStatement ps, int index, UUID[] values) throws SQLException {
        Array array = ps.getConnection().createArrayOf("uuid", values);
        ps.setArray(index, array);
    }

    private static ReportDtos.WrongItemsView extract(ResultSet rs) throws SQLException {
        List<ReportDtos.WrongItemView> items = new ArrayList<>();
        int totalItems = 0;
        int offeringCount = 0;
        while (rs.next()) {
            if (items.isEmpty()) {
                totalItems = rs.getInt("total_items");
                offeringCount = rs.getInt("offering_count");
            }
            BigDecimal rate = rs.getBigDecimal("correct_rate");
            items.add(new ReportDtos.WrongItemView(
                    rs.getObject("item_id", UUID.class).toString(),
                    rs.getInt("number"),
                    rs.getString("stem"),
                    rs.getString("question_type"),
                    rs.getBigDecimal("points"),
                    rs.getLong("answered"),
                    rs.getLong("wrong"),
                    rate,
                    rs.getString("top_wrong_answer"),
                    rs.getLong("top_wrong_count")));
        }
        return new ReportDtos.WrongItemsView(items, totalItems, offeringCount);
    }
}
