package com.teachloop.grading.ai;

import java.util.UUID;

/**
 * D-16：每次判分按作业创建者解析 Key，再创建/复用客户端。
 * 不再在应用启动时把管理员 Key 固化进单例客户端。
 */
@FunctionalInterface
public interface LlmClientProvider {

    LlmClient forTeacher(UUID teacherId);

    /** 本次客户端使用的 Key 来源（user / global / env）；用于审计，不暴露 Key。 */
    default String source(UUID teacherId) {
        return null;
    }
}
