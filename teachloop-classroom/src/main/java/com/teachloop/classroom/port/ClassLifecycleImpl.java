package com.teachloop.classroom.port;

import com.teachloop.classroom.domain.ClassMemberRepository;
import com.teachloop.classroom.domain.ClassRepository;
import com.teachloop.classroom.domain.MemberStatus;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 班级生命周期端口实现（012 / FR-222）。
 *
 * <p>为什么不给 {@code sitting} 一个 join：{@code classes} / {@code class_members} 只有一个属主，
 * 跨模块只能问端口。被问的是两件事：
 *
 * <ul>
 *   <li>{@code archived}：这个班是否已结课——新建作业、提交、今日页聚合都要问它；</li>
 *   <li>{@code active}：**当前请求的登录者**是否还是这个班的 {@code ACTIVE} 成员。</li>
 * </ul>
 *
 * <p>{@code active} 依赖 HTTP 请求里的 {@code currentUser}（identity 的会话过滤器放进去的）。
 * 后台任务（到点自动交卷、夜间批改）没有请求上下文，此时 {@code active=false}——调用方据此
 * 判断「不是某个具体学生」时不要拿它当准入条件，应按学生 id 显式走
 * {@code Ports.ClassMembership.classIdsOf(studentId)}（那一路带 ACTIVE 过滤）。
 */
@Component
public class ClassLifecycleImpl implements Ports.ClassLifecycle {

    private final ClassRepository classes;
    private final ClassMemberRepository members;

    public ClassLifecycleImpl(ClassRepository classes, ClassMemberRepository members) {
        this.classes = classes;
        this.members = members;
    }

    @Override
    public Optional<Ports.ClassState> state(UUID classId) {
        if (classId == null) {
            return Optional.empty();
        }
        return classes.findById(classId).map(c -> new Ports.ClassState(
                c.getId(), c.isArchived(), isCurrentUserActiveMember(c.getId())));
    }

    private boolean isCurrentUserActiveMember(UUID classId) {
        CurrentUser me = currentUser();
        if (me == null) {
            return false;
        }
        return members.existsByClassIdAndStudentIdAndStatus(classId, me.id(), MemberStatus.ACTIVE);
    }

    private static CurrentUser currentUser() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object user = attributes.getAttribute("currentUser", RequestAttributes.SCOPE_REQUEST);
        return user instanceof CurrentUser current ? current : null;
    }
}
