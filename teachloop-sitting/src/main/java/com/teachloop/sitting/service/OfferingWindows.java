package com.teachloop.sitting.service;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.sitting.domain.Offering;
import com.teachloop.sitting.domain.OfferingClass;
import com.teachloop.sitting.domain.OfferingClassRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 一场布置的「有效窗口」：布置级默认值 + 按班覆盖（T198 / AC-26.4）。
 *
 * <p>学生同时在多个参与班且各班覆盖不同时，取最早的 <code>due_at</code> 与最短的时长——
 * 宁可让学生早交，也不能因为跨班而多给时间。
 *
 * <p>012 起这里还承接「结课班级能不能交」：同一个作业可以发给多个班，
 * **只要还有一个未归档的班仍然包含这个学生，就允许提交**（T585 的多班规则）。
 * 判断只走端口 {@code Ports.ClassLifecycle} / {@code Ports.ClassMembership}，不 join classroom 的表。
 */
@Component
public class OfferingWindows {

    private static final Logger log = LoggerFactory.getLogger(OfferingWindows.class);

    private final OfferingClassRepository offeringClasses;
    private final Ports.ClassMembership membership;
    private final Ports.ClassLifecycle lifecycle;
    private final SnapshotCodec codec;

    public OfferingWindows(OfferingClassRepository offeringClasses, Ports.ClassMembership membership,
                           Ports.ClassLifecycle lifecycle, SnapshotCodec codec) {
        this.offeringClasses = offeringClasses;
        this.membership = membership;
        this.lifecycle = lifecycle;
        this.codec = codec;
    }

    /** 有效截止时间与时长。 */
    public record Window(Instant dueAt, int durationMinutes) {
    }

    public Window forStudent(Offering offering, UUID studentId) {
        return resolve(offering, offeringClasses.findByOfferingId(offering.getId()),
                membership.classIdsOf(studentId));
    }

    /** 布置级默认窗口（不含任何按班覆盖），用于教师端展示「本场设置」。 */
    public Window forOffering(Offering offering) {
        return new Window(offering.getDueAt(), defaultDuration(offering));
    }

    /** 学生是否属于本场的任一参与班（012 起：只算还在班的成员）。 */
    public boolean isParticipant(Offering offering, UUID studentId) {
        return !classesOf(offering, studentId).isEmpty();
    }

    /**
     * 学生与本场布置的交集班级（他仍是 ACTIVE 成员的那些参与班）。
     *
     * <p>返回空 = 不是参与者；调用方别再拿「布置的班级」当参与者判断。
     */
    public List<UUID> classesOf(Offering offering, UUID studentId) {
        Collection<UUID> mine = membership.classIdsOf(studentId);
        return offeringClasses.findByOfferingId(offering.getId()).stream()
                .map(OfferingClass::getClassId)
                .filter(mine::contains)
                .distinct()
                .toList();
    }

    /**
     * 提交前的结课检查（AC-98.7 / FR-220 / T585）。
     *
     * <p>规则：**至少一个未归档的班仍然包含该生** 才放行。所以「作业发给两个班、只结了其中一个」
     * 时该生仍可提交——一个班结课不能把另一个班的学生一起锁死。
     *
     * <p>没有任何参与班（作业没挂班，或该生已不在班）：交给 {@code requireParticipant} 处理，
     * 这里直接放行，避免把 404 变成 409。
     */
    public void requireSubmittable(Offering offering, UUID studentId) {
        List<UUID> candidates = classesOf(offering, studentId);
        if (candidates.isEmpty()) {
            return;
        }
        for (UUID classId : candidates) {
            if (!archived(classId)) {
                return;
            }
        }
        throw ApiException.classArchived("error.class.archived");
    }

    /** 这个班是否已结课（失败按未归档放行：宁可放行，不误伤考试中的学生）。 */
    public boolean archived(UUID classId) {
        try {
            return lifecycle.state(classId).map(Ports.ClassState::archived).orElse(false);
        } catch (RuntimeException e) {
            log.warn("class lifecycle lookup failed for {}, treating it as not archived: {}", classId, e.toString());
            return false;
        }
    }

    Window resolve(Offering offering, List<OfferingClass> rows, Collection<UUID> studentClassIds) {
        int duration = defaultDuration(offering);
        Instant due = offering.getDueAt();

        List<OfferingClass> matched = rows.stream()
                .filter(oc -> studentClassIds.contains(oc.getClassId()))
                .toList();
        for (OfferingClass oc : matched) {
            Instant candidateDue = oc.getDueAtOverride() != null ? oc.getDueAtOverride() : offering.getDueAt();
            int candidateDuration = oc.getDurationOverride() != null
                    ? oc.getDurationOverride()
                    : (offering.getDurationMinutes() != null ? offering.getDurationMinutes() : duration);
            if (candidateDue.isBefore(due)) {
                due = candidateDue;
            }
            if (candidateDuration < duration) {
                duration = candidateDuration;
            }
        }
        return new Window(due, duration);
    }

    private int defaultDuration(Offering offering) {
        if (offering.getDurationMinutes() != null) {
            return offering.getDurationMinutes();
        }
        Integer fromSnapshot = codec.read(offering.getPaperSnapshot()).durationMinutes();
        return fromSnapshot == null ? 0 : fromSnapshot;
    }
}
