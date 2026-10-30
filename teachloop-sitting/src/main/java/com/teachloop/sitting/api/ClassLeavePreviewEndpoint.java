package com.teachloop.sitting.api;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.sitting.domain.Offering;
import com.teachloop.sitting.domain.OfferingClass;
import com.teachloop.sitting.domain.OfferingClassRepository;
import com.teachloop.sitting.domain.OfferingRepository;
import com.teachloop.sitting.domain.OfferingStatus;
import com.teachloop.sitting.domain.SubmissionRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

/**
 * 退班前的确认数据（012 / AC-96.5 / T582）。
 *
 * <p>为什么这个接口在 sitting 而不是 classroom：未交作业数 = 「这个班发布中的布置」减
 * 「这个学生已交卷的布置」，布置与提交都是 sitting 的表（每张表只有一个属主）。
 * classroom 只提供「你是不是还在班」这一层（{@code Ports.ClassMembership}），
 * 通过端口问，不跨模块 join。
 *
 * <p>路径挂在 {@code /api/classes/{id}} 是因为它按班级回答学生的问题；
 * 数据属主仍是 sitting。响应里带 {@code archived}，前端据此决定确认文案要不要提「已结课」。
 */
@RestController
@RequestMapping("/api/classes/{classId}")
public class ClassLeavePreviewEndpoint {

    private final OfferingRepository offerings;
    private final OfferingClassRepository offeringClasses;
    private final SubmissionRepository submissions;
    private final Ports.ClassMembership membership;
    private final Ports.ClassLifecycle lifecycle;

    public ClassLeavePreviewEndpoint(OfferingRepository offerings, OfferingClassRepository offeringClasses,
                                     SubmissionRepository submissions, Ports.ClassMembership membership,
                                     Ports.ClassLifecycle lifecycle) {
        this.offerings = offerings;
        this.offeringClasses = offeringClasses;
        this.submissions = submissions;
        this.membership = membership;
        this.lifecycle = lifecycle;
    }

    public record LeavePreviewView(String classId, long pendingAssignments, long submittedAssignments,
                                   long totalAssignments, boolean archived) {
    }

    @GetMapping("/leave-preview")
    public LeavePreviewView preview(@PathVariable UUID classId, @RequestAttribute("currentUser") CurrentUser me) {
        if (!me.is("STUDENT")) {
            throw ApiException.forbidden("error.class.studentOnly");
        }
        // 不在班（或已退班）一律 404：不泄露班级存在性，也避免拿退班后的状态再算一遍。
        if (!membership.classIdsOf(me.id()).contains(classId)) {
            throw ApiException.notFound("error.class.notMember");
        }
        Set<UUID> inClass = offeringClasses.findByClassId(classId).stream()
                .map(OfferingClass::getOfferingId)
                .collect(Collectors.toSet());
        List<Offering> published = offerings.findByStatusOrderByDueAtDesc(OfferingStatus.PUBLISHED).stream()
                .filter(o -> inClass.contains(o.getId()))
                .toList();
        long submitted = published.isEmpty() ? 0
                : submissions.countSubmittedOfferings(me.id(), published.stream().map(Offering::getId).toList());
        long total = published.size();
        long pending = Math.max(0, total - submitted);
        boolean archived = lifecycle.state(classId).map(Ports.ClassState::archived).orElse(false);
        return new LeavePreviewView(classId.toString(), pending, submitted, total, archived);
    }
}
