package com.teachloop.classroom.api;

import com.teachloop.classroom.domain.*;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.error.ErrorCode;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 班级与成员（T040–T043）+ 012 班级生命周期。
 *
 * <p>012 之后班级有终点、成员关系也有终点，两者都**只写状态、不删行**（FR-215）：
 * 退班 → {@code class_members.status='LEFT'}；结课 → {@code classes.archived=true}。
 * 结课班级的学生侧只读（AC-98.2），邀请码失效（AC-98.3），提交与新建作业由 sitting 侧经
 * {@code Ports.ClassLifecycle} 拒绝（AC-98.7 / FR-220）。
 */
@RestController
@RequestMapping("/api/classes")
public class ClassEndpoint {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** 邀请码无效与班级已结课共用同一句：不得泄露「这个班存在但归档了」（AC-98.3）。 */
    private static final String INVITE_INVALID = "邀请码无效";

    private final ClassRepository classes;
    private final ClassMemberRepository members;
    private final Ports.StudentDirectory students;
    private final Ports.ClassSubmissionStats submissionStats;
    private final AuditPort audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ClassEndpoint(ClassRepository classes, ClassMemberRepository members,
                         Ports.StudentDirectory students, Ports.ClassSubmissionStats submissionStats,
                         AuditPort audit, Clock clock) {
        this.classes = classes;
        this.members = members;
        this.students = students;
        this.submissionStats = submissionStats;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 建班请求。011 / AC-95.2：{@code payMode} / {@code priceCents} / {@code currency} 都是**可选**的，
     * 不传时与今天行为完全一致（免费班 FREE / 0 / CNY）；传了付费值则 400——第一期不开放付费班。
     */
    public record CreateClass(@NotBlank String name, String payMode, Integer priceCents, String currency) {
    }

    public record JoinClass(@NotBlank String inviteCode) {
    }

    /** 班级视图：{@code memberCount} 只数 ACTIVE；{@code archived} 让学生端区分「进行中 / 已结束」。 */
    public record ClassView(String id, String name, String inviteCode, long memberCount, boolean archived) {
    }

    /** 退班 / 移出的结果；前端据此回显状态，不必再取整个列表。 */
    public record MembershipView(String classId, String studentId, String status, Instant leftAt) {
    }

    /**
     * 建班。
     *
     * <p>T561 / AC-93.4：教师资料未补全（缺手机号或邮箱）时 403 {@code PROFILE_INCOMPLETE}——
     * 这是「资料补全不再整站 403」之后**保留强制**的两个写操作之一。判定由 identity 的
     * {@code ProfileCompletionFilter} 写进请求属性 {@code teachloop.profileIncomplete}，这里只消费，
     * 不重复写「手机号 + 邮箱 + 管理员豁免」的逻辑。
     *
     * <p>011 / AC-95.2：{@code payMode != FREE}（或价格非 0、币种非 CNY）→ 400，第一期不开放付费班。
     */
    @PostMapping
    @Transactional
    public ClassView create(@RequestBody @Valid CreateClass body, @RequestAttribute("currentUser") CurrentUser me,
                            @RequestAttribute(name = "teachloop.profileIncomplete", required = false)
                            Boolean profileIncomplete) {
        requireTeacher(me);
        requireProfileComplete(profileIncomplete);
        requireFreeClass(body);
        var c = new ClassEntity(UUID.randomUUID(), body.name().trim(), me.id(), newCode(), Instant.now(clock));
        classes.save(c);
        return view(c);
    }

    @GetMapping
    public List<ClassView> mine(@RequestAttribute("currentUser") CurrentUser me) {
        List<ClassEntity> list = me.is("STUDENT")
                ? classes.findForStudent(me.id())
                : classes.findByTeacherIdOrderByCreatedAtDesc(me.id());
        return list.stream().map(this::view).toList();
    }

    /**
     * 用邀请码加入（012 / AC-96.3 / FR-217）。
     *
     * <p>三种情况：没行 → 建 {@code ACTIVE} 行；有 {@code LEFT} 行 → 复用同一行改回 {@code ACTIVE}，
     * {@code joined_at} 不变、写 {@code rejoined_at}；已经是 {@code ACTIVE} → 什么都不改（幂等）。
     * 已结课的班邀请码一律 404，响应里不带任何「已归档」字样。
     */
    @PostMapping("/join")
    @Transactional
    public ClassView join(@RequestBody @Valid JoinClass body, @RequestAttribute("currentUser") CurrentUser me) {
        if (!me.is("STUDENT")) {
            throw ApiException.forbidden("只有学生可以用邀请码加入班级");
        }
        ClassEntity c = classes.findByInviteCode(body.inviteCode().trim().toUpperCase())
                .orElseThrow(() -> ApiException.notFound(INVITE_INVALID));
        if (c.isArchived()) {
            throw ApiException.notFound(INVITE_INVALID);
        }
        // 011 / AC-95.3：付费班暂未开放。校验放在「归档」之后——归档班的邀请码仍按 404 处理，
        // 不因为收费字段多暴露一种状态。
        if (c.isPaid()) {
            throw ApiException.conflict(ClassPay.MESSAGE_PAID_NOT_AVAILABLE);
        }
        Instant now = Instant.now(clock);
        ClassMember existing = members.findByClassIdAndStudentId(c.getId(), me.id()).orElse(null);
        if (existing == null) {
            members.save(new ClassMember(UUID.randomUUID(), c.getId(), me.id(), now));
        } else if (!existing.isActive()) {
            existing.rejoin(now);
            members.save(existing);
        }
        return view(c);
    }

    /** 学生退出班级（US-96 / T582）：写 LEFT + left_at，不删行；已结课的班也允许退出。 */
    @DeleteMapping("/{id}/membership")
    @Transactional
    public MembershipView leave(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        if (!me.is("STUDENT")) {
            throw ApiException.forbidden("error.class.studentOnly");
        }
        ClassMember row = members.findByClassIdAndStudentId(id, me.id())
                .filter(ClassMember::isActive)
                .orElseThrow(() -> ApiException.notFound("error.class.notMember"));
        Instant now = Instant.now(clock);
        row.leave(now);
        members.save(row);
        audit.record(new AuditEvent(me.id(), "class:leave", "class", id.toString(),
                Map.of("studentId", me.id().toString(), "leftAt", now.toString())));
        return new MembershipView(id.toString(), me.id().toString(), row.getStatus().name(), row.getLeftAt());
    }

    /** 老师移出学生（US-97 / T582）：仅属主，非属主 404；同样只写 LEFT。 */
    @DeleteMapping("/{id}/members/{studentId}")
    @Transactional
    public MembershipView removeMember(@PathVariable UUID id, @PathVariable UUID studentId,
                                       @RequestAttribute("currentUser") CurrentUser me) {
        requireOwned(id, me);
        ClassMember row = members.findByClassIdAndStudentId(id, studentId)
                .filter(ClassMember::isActive)
                .orElseThrow(() -> ApiException.notFound("error.class.memberNotFound"));
        Instant now = Instant.now(clock);
        row.leave(now);
        members.save(row);
        audit.record(new AuditEvent(me.id(), "class:remove-member", "class", id.toString(),
                Map.of("studentId", studentId.toString(), "leftAt", now.toString())));
        return new MembershipView(id.toString(), studentId.toString(), row.getStatus().name(), row.getLeftAt());
    }

    /** 结课（US-98 / T584）：置 {@code archived=true}，幂等；只有属主能调用。 */
    @PostMapping("/{id}/archive")
    @Transactional
    public ClassView archive(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        ClassEntity c = requireOwned(id, me);
        if (!c.isArchived()) {
            c.setArchived(true);
            classes.save(c);
            audit.record(AuditEvent.of(me.id(), "class:archive", "class", id.toString()));
        }
        return view(c);
    }

    /** 恢复（AC-98.5）：置 {@code archived=false}，幂等；历史数据一律不动。 */
    @PostMapping("/{id}/restore")
    @Transactional
    public ClassView restore(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        ClassEntity c = requireOwned(id, me);
        if (c.isArchived()) {
            c.setArchived(false);
            classes.save(c);
            audit.record(AuditEvent.of(me.id(), "class:restore", "class", id.toString()));
        }
        return view(c);
    }

    public record MemberView(String studentId, String displayName, int submissionCount, String status,
                             Instant leftAt) {
    }

    /**
     * 教师可见成员姓名与提交数（T042 / AC-97.2）。
     *
     * <p>默认只返回 {@code ACTIVE}；勾「含已离开」时前端带 {@code ?includeLeft=1}。
     * 学生访问 403。
     */
    @GetMapping("/{id}/members")
    public List<MemberView> memberList(@PathVariable UUID id,
                                       @RequestParam(name = "includeLeft", required = false) String includeLeft,
                                       @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能查看班级成员列表");
        }
        requireOwned(id, me);
        boolean withLeft = "1".equals(includeLeft) || "true".equalsIgnoreCase(includeLeft);
        List<ClassMember> rows = withLeft
                ? members.findByClassIdOrderByJoinedAtAsc(id)
                : members.findByClassIdAndStatusOrderByJoinedAtAsc(id, MemberStatus.ACTIVE);
        Map<UUID, String> names = students.displayNames(rows.stream().map(ClassMember::getStudentId).toList());
        Map<UUID, Integer> counts = new java.util.HashMap<>();
        submissionStats.submissionCounts(id).forEach(c -> counts.put(c.studentId(), c.count()));
        return rows.stream().map(m -> new MemberView(m.getStudentId().toString(),
                names.getOrDefault(m.getStudentId(), ""), counts.getOrDefault(m.getStudentId(), 0),
                m.getStatus().name(), m.getLeftAt())).toList();
    }

    @PostMapping("/{id}/invite/regenerate")
    @Transactional
    public ClassView regenerateInvite(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        ClassEntity c = requireOwned(id, me);
        c.setInviteCode(newCode());
        return view(c);
    }

    private ClassEntity requireOwned(UUID id, CurrentUser me) {
        ClassEntity c = classes.findById(id).orElseThrow(() -> ApiException.notFound("班级不存在"));
        // 越权一律 404，不泄漏存在性。班级只属于师生，管理员没有例外（2026-10-27）。
        if (!c.getTeacherId().equals(me.id())) {
            throw ApiException.notFound("班级不存在");
        }
        return c;
    }

    private static void requireTeacher(CurrentUser me) {
        if (!me.is("TEACHER")) {
            throw ApiException.forbidden("error.class.teacherOnly");
        }
    }

    /**
     * T561 / AC-93.4：资料未补全的教师不能建班。属性由 identity 的过滤器写入，
     * 缺失（过滤器不在链上）按放行处理——真正的强制点在过滤器覆盖的全部真实请求上。
     */
    private static void requireProfileComplete(Boolean profileIncomplete) {
        if (Boolean.TRUE.equals(profileIncomplete)) {
            throw new ApiException(ErrorCode.PROFILE_INCOMPLETE, "error.profile.incomplete");
        }
    }

    /** 011 / AC-95.2：第一期只允许免费班；可选项不传 = FREE / 0 / CNY，与今天一致。 */
    private static void requireFreeClass(CreateClass body) {
        boolean free = body.payMode() == null || body.payMode().isBlank()
                || ClassPay.Mode.FREE.name().equalsIgnoreCase(body.payMode().trim());
        boolean zeroPrice = body.priceCents() == null || body.priceCents() == 0;
        boolean cny = body.currency() == null || body.currency().isBlank()
                || ClassPay.DEFAULT_CURRENCY.equalsIgnoreCase(body.currency().trim());
        if (!free || !zeroPrice || !cny) {
            throw ApiException.badRequest(ClassPay.MESSAGE_CREATE_NOT_SUPPORTED);
        }
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private ClassView view(ClassEntity c) {
        return new ClassView(c.getId().toString(), c.getName(), c.getInviteCode(),
                members.countByClassIdAndStatus(c.getId(), MemberStatus.ACTIVE), c.isArchived());
    }
}
