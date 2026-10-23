package com.teachloop.sitting.service;

import com.teachloop.shared.error.ApiException;
import com.teachloop.sitting.domain.TeacherGradingPreference;
import com.teachloop.sitting.domain.TeacherGradingPreferenceRepository;
import com.teachloop.sitting.domain.TriggerMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 教师级作业批改时机（决策 010）。
 *
 * <p>没有保存过偏好的教师按默认值解释：固定时间 02:30 Asia/Shanghai、启动补跑。
 * 视图永远返回可展示的值，前端不需要自己补默认。
 */
@Service
public class TeacherGradingPreferenceService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public record View(String triggerMode, String fixedTime, String timeZone, boolean catchUp,
                       String lastFixedRunAt, String updatedAt) {
    }

    public record UpdateRequest(String triggerMode, String fixedTime, String timeZone, Boolean catchUp) {
    }

    private final TeacherGradingPreferenceRepository repo;
    private final Clock clock;
    private final LocalTime defaultTime;
    private final String defaultZone;
    private final boolean defaultCatchUp;

    public TeacherGradingPreferenceService(
            TeacherGradingPreferenceRepository repo, Clock clock,
            @org.springframework.beans.factory.annotation.Value(
                    "${teachloop.grading.assignment-default-time:02:30}") String defaultTime,
            @org.springframework.beans.factory.annotation.Value(
                    "${teachloop.grading.assignment-default-zone:Asia/Shanghai}") String defaultZone,
            @org.springframework.beans.factory.annotation.Value(
                    "${teachloop.grading.assignment-default-catch-up:true}") boolean defaultCatchUp) {
        this.repo = repo;
        this.clock = clock;
        this.defaultTime = LocalTime.parse(defaultTime.strip(), HH_MM);
        this.defaultZone = ZoneId.of(defaultZone.strip()).getId();
        this.defaultCatchUp = defaultCatchUp;
    }

    public View view(UUID teacherId) {
        return toView(getOrDefault(teacherId));
    }

    @Transactional
    public View update(UUID teacherId, UUID actorId, UpdateRequest req) {
        TeacherGradingPreference p = repo.findById(teacherId)
                .orElseGet(() -> defaults(teacherId, Instant.now(clock)));
        if (req.triggerMode() != null && !req.triggerMode().isBlank()) {
            p.setTriggerMode(parseMode(req.triggerMode()));
        }
        if (req.fixedTime() != null && !req.fixedTime().isBlank()) {
            p.setFixedTime(parseTime(req.fixedTime()));
        }
        if (req.timeZone() != null && !req.timeZone().isBlank()) {
            p.setTimeZone(parseZone(req.timeZone()));
        }
        if (req.catchUp() != null) {
            p.setCatchUp(req.catchUp());
        }
        p.setUpdatedAt(Instant.now(clock));
        p.setUpdatedBy(actorId);
        repo.save(p);
        return toView(p);
    }

    /** 调度器读取：没有行就返回一个未落库的默认偏好。 */
    public TeacherGradingPreference getOrDefault(UUID teacherId) {
        return repo.findById(teacherId)
                .orElseGet(() -> defaults(teacherId, Instant.now(clock)));
    }

    public List<TeacherGradingPreference> all() {
        return repo.findAll();
    }

    /** 固定时间跑过之后记一笔；没有行时顺手落一行默认值。 */
    @Transactional
    public void markFixedRun(UUID teacherId, Instant now) {
        TeacherGradingPreference p = repo.findById(teacherId)
                .orElseGet(() -> defaults(teacherId, now));
        p.setLastFixedRunAt(now);
        p.setUpdatedAt(now);
        repo.save(p);
    }

    private TeacherGradingPreference defaults(UUID teacherId, Instant now) {
        TeacherGradingPreference p = new TeacherGradingPreference(teacherId, now);
        p.setFixedTime(defaultTime);
        p.setTimeZone(defaultZone);
        p.setCatchUp(defaultCatchUp);
        return p;
    }

    private static View toView(TeacherGradingPreference p) {
        return new View(p.getTriggerMode().name(),
                p.getFixedTime() == null ? "02:30" : p.getFixedTime().format(HH_MM),
                p.getTimeZone(),
                p.isCatchUp(),
                p.getLastFixedRunAt() == null ? null : p.getLastFixedRunAt().toString(),
                p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString());
    }

    private static TriggerMode parseMode(String raw) {
        try {
            return TriggerMode.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("error.grading.triggerMode");
        }
    }

    private static LocalTime parseTime(String raw) {
        try {
            return LocalTime.parse(raw.strip(), HH_MM);
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest("error.grading.fixedTime");
        }
    }

    private static String parseZone(String raw) {
        try {
            return ZoneId.of(raw.strip()).getId();
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("error.grading.timeZone");
        }
    }
}
