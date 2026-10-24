package com.teachloop.sitting.service;

import com.teachloop.sitting.domain.OfferingRepository;
import com.teachloop.sitting.domain.TeacherGradingPreference;
import com.teachloop.sitting.domain.TriggerMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 教师级作业批改调度（决策 010）。
 *
 * <p>每分钟扫一次，按每个教师自己的偏好决定：
 * <ul>
 *   <li>{@link TriggerMode#FIXED_TIME}：到达教师设置的本地时刻后派发该教师所有排队中的作业；
 *       同一本地日期最多跑一次（{@code last_fixed_run_at}）。</li>
 *   <li>{@link TriggerMode#DUE_DATE}：过了 {@code due_at + grace_hours} 就派发，不受时段限制。</li>
 * </ul>
 *
 * <p>考试不经过这里：考试仍由 {@link GradingDispatchJob} 按 due+grace 派发。
 */
@Component
public class TeacherGradingScheduler {

    private static final Logger log = LoggerFactory.getLogger(TeacherGradingScheduler.class);

    private final TeacherGradingPreferenceService preferences;
    private final OfferingRepository offerings;
    private final OfferingGradingDispatcher dispatcher;
    private final Clock clock;

    public TeacherGradingScheduler(TeacherGradingPreferenceService preferences,
                                   OfferingRepository offerings,
                                   OfferingGradingDispatcher dispatcher,
                                   Clock clock) {
        this.preferences = preferences;
        this.offerings = offerings;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${teachloop.grading.preference-scan-ms:60000}",
            initialDelayString = "${teachloop.grading.preference-scan-ms:60000}")
    public void scan() {
        scan(Instant.now(clock));
    }

    /** 可注入 now 的扫描入口；测试直接调它，不依赖真实时钟。 */
    public int scan(Instant now) {
        Set<UUID> teachers = new LinkedHashSet<>();
        for (TeacherGradingPreference p : preferences.all()) {
            teachers.add(p.getTeacherId());
        }
        teachers.addAll(offerings.findTeacherIdsWithQueuedAssignments());

        int dispatched = 0;
        for (UUID teacherId : teachers) {
            TeacherGradingPreference p = preferences.getOrDefault(teacherId);
            try {
                if (p.getTriggerMode() == TriggerMode.FIXED_TIME) {
                    if (fixedRunDue(p, now)) {
                        dispatched += dispatcher.dispatchAssignmentsForTeacher(teacherId);
                        preferences.markFixedRun(teacherId, now);
                    }
                } else {
                    dispatched += dispatcher.dispatchAssignmentsDueForTeacher(teacherId, now);
                }
            } catch (RuntimeException ex) {
                log.warn("Assignment dispatch failed for teacher {}, retrying on next scan: {}", teacherId, ex.toString());
            }
        }
        return dispatched;
    }

    /**
     * 固定时间是否该跑：取「最近一个已经到达的本地时刻」。
     *
     * <p>用最近一个而不是今天的时刻，服务器在 23:59 的批次之后、次日 00:10 启动时也能补跑。
     * `catchUp=false` 时只允许在时刻后 5 分钟内跑，错过就等下一次。
     */
    public boolean fixedRunDue(TeacherGradingPreference p, Instant now) {
        ZoneId zone = ZoneId.of(p.getTimeZone());
        ZonedDateTime nowZ = now.atZone(zone);
        ZonedDateTime today = nowZ.toLocalDate().atTime(p.getFixedTime()).atZone(zone);
        ZonedDateTime scheduled = nowZ.isBefore(today) ? today.minusDays(1) : today;
        Instant last = p.getLastFixedRunAt();
        if (last != null && !last.isBefore(scheduled.toInstant())) {
            return false;
        }
        if (nowZ.isAfter(scheduled.plusMinutes(5)) && !p.isCatchUp()) {
            return false;
        }
        return true;
    }

    /** 给测试/运维看的下一次固定时刻；仅用于日志与排查。 */
    public Instant nextFixedRunAt(TeacherGradingPreference p, Instant now) {
        ZoneId zone = ZoneId.of(p.getTimeZone());
        ZonedDateTime nowZ = now.atZone(zone);
        LocalDate date = nowZ.toLocalDate();
        ZonedDateTime today = date.atTime(p.getFixedTime()).atZone(zone);
        ZonedDateTime next = nowZ.isBefore(today) ? today : today.plusDays(1);
        return next.toInstant();
    }
}
