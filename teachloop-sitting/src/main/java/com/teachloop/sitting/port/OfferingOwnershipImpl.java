package com.teachloop.sitting.port;

import com.teachloop.shared.port.Ports;
import com.teachloop.sitting.domain.OfferingRepository;
import com.teachloop.sitting.domain.SubmissionRepository;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * T329：把「这批提交属不属于这位老师」这一个问题答清楚。
 *
 * <p>归属信息只有 sitting 知道（{@code offerings.created_by}），所以由本模块实现；
 * 阅卷模块拿到结果后自己过滤，不跨模块读 {@code offerings} 表。
 */
@Component
public class OfferingOwnershipImpl implements Ports.OfferingOwnership {

    private final SubmissionRepository submissions;
    private final OfferingRepository offerings;

    public OfferingOwnershipImpl(SubmissionRepository submissions, OfferingRepository offerings) {
        this.submissions = submissions;
        this.offerings = offerings;
    }

    @Override
    public Set<UUID> ownedSubmissionIds(Collection<UUID> submissionIds, UUID teacherId) {
        if (submissionIds == null || submissionIds.isEmpty() || teacherId == null) {
            return Set.of();
        }
        return Set.copyOf(submissions.findOwnedIds(submissionIds, teacherId));
    }

    @Override
    public Set<UUID> activeSubmissionIds(Collection<UUID> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(submissions.findActiveIds(submissionIds));
    }

    @Override
    public Set<UUID> ownedOfferingIds(Collection<UUID> offeringIds, UUID teacherId) {
        if (offeringIds == null || offeringIds.isEmpty() || teacherId == null) {
            return Set.of();
        }
        return Set.copyOf(offerings.findOwnedIds(offeringIds, teacherId));
    }
}
