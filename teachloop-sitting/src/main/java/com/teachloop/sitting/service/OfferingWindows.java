package com.teachloop.sitting.service;

import com.teachloop.shared.port.Ports;
import com.teachloop.sitting.domain.Offering;
import com.teachloop.sitting.domain.OfferingClass;
import com.teachloop.sitting.domain.OfferingClassRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 一场布置的「有效窗口」：布置级默认值 + 按班覆盖（T198 / AC-26.4）。
 *
 * <p>学生同时在多个参与班且各班覆盖不同时，取最早的 <code>due_at</code> 与最短的时长——
 * 宁可让学生早交，也不能因为跨班而多给时间。
 */
@Component
public class OfferingWindows {

    private final OfferingClassRepository offeringClasses;
    private final Ports.ClassMembership membership;
    private final SnapshotCodec codec;

    public OfferingWindows(OfferingClassRepository offeringClasses, Ports.ClassMembership membership,
                           SnapshotCodec codec) {
        this.offeringClasses = offeringClasses;
        this.membership = membership;
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

    /** 学生是否属于本场的任一参与班。 */
    public boolean isParticipant(Offering offering, UUID studentId) {
        Collection<UUID> mine = membership.classIdsOf(studentId);
        return offeringClasses.findByOfferingId(offering.getId()).stream()
                .anyMatch(oc -> mine.contains(oc.getClassId()));
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
