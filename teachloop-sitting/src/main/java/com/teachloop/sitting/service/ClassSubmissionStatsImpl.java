package com.teachloop.sitting.service;

import com.teachloop.shared.port.Ports;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** T042：按班统计每名学生的提交数；只读自己的表。 */
@Component
public class ClassSubmissionStatsImpl implements Ports.ClassSubmissionStats {

    private final JdbcTemplate jdbc;

    public ClassSubmissionStatsImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Ports.ClassSubmissionCount> submissionCounts(UUID classId) {
        List<Ports.ClassSubmissionCount> result = new ArrayList<>();
        jdbc.query("select s.student_id, count(*) as c "
                        + "from submissions s join offering_classes oc on oc.offering_id = s.offering_id "
                        + "where oc.class_id = ? group by s.student_id",
                rs -> {
                    result.add(new Ports.ClassSubmissionCount(
                            rs.getObject("student_id", UUID.class), rs.getInt("c")));
                }, classId);
        return result;
    }
}
