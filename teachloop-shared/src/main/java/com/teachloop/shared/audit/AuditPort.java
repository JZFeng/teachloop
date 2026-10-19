package com.teachloop.shared.audit;

/** 审计由可观测模块实现，但必须在业务事务内写入（宪法第二条）。 */
public interface AuditPort {
    void record(AuditEvent event);
}
