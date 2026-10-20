package com.teachloop.sitting.service;

import com.teachloop.shared.port.Ports;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** T206：试卷被布置次数，只读 sitting 自己的 offerings 表。 */
@Component
public class PaperUsageImpl implements Ports.PaperUsage {

    private final JdbcTemplate jdbc;

    public PaperUsageImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int assignmentCount(UUID paperId) {
        Integer n = jdbc.queryForObject("select count(*) from offerings where paper_id = ?", Integer.class, paperId);
        return n == null ? 0 : n;
    }
}
