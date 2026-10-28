package com.teachloop.classroom.api;

import com.teachloop.classroom.domain.*;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.shared.error.ApiException;
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

@RestController
@RequestMapping("/api/classes")
public class ClassEndpoint {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final ClassRepository classes;
    private final ClassMemberRepository members;
    private final Ports.StudentDirectory students;
    private final Ports.ClassSubmissionStats submissionStats;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ClassEndpoint(ClassRepository classes, ClassMemberRepository members,
                         Ports.StudentDirectory students, Ports.ClassSubmissionStats submissionStats, Clock clock) {
        this.classes = classes;
        this.members = members;
        this.students = students;
        this.submissionStats = submissionStats;
        this.clock = clock;
    }

    public record CreateClass(@NotBlank String name) {
    }

    public record JoinClass(@NotBlank String inviteCode) {
    }

    public record ClassView(String id, String name, String inviteCode, long memberCount) {
    }

    @PostMapping
    @Transactional
    public ClassView create(@RequestBody @Valid CreateClass body, @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
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

    @PostMapping("/join")
    @Transactional
    public ClassView join(@RequestBody @Valid JoinClass body, @RequestAttribute("currentUser") CurrentUser me) {
        if (!me.is("STUDENT")) {
            throw ApiException.forbidden("只有学生可以用邀请码加入班级");
        }
        ClassEntity c = classes.findByInviteCode(body.inviteCode().trim().toUpperCase())
                .orElseThrow(() -> ApiException.notFound("邀请码无效"));
        if (!members.existsByClassIdAndStudentId(c.getId(), me.id())) {
            members.save(new ClassMember(UUID.randomUUID(), c.getId(), me.id(), Instant.now(clock)));
        }
        return view(c);
    }

    public record MemberView(String studentId, String displayName, int submissionCount) {
    }

    /** T042：教师可见成员姓名与提交数；学生访问 403。 */
    @GetMapping("/{id}/members")
    public List<MemberView> memberList(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能查看班级成员列表");
        }
        requireOwned(id, me);
        List<ClassMember> rows = members.findByClassId(id);
        Map<UUID, String> names = students.displayNames(rows.stream().map(ClassMember::getStudentId).toList());
        Map<UUID, Integer> counts = new java.util.HashMap<>();
        submissionStats.submissionCounts(id).forEach(c -> counts.put(c.studentId(), c.count()));
        return rows.stream().map(m -> new MemberView(m.getStudentId().toString(),
                names.getOrDefault(m.getStudentId(), ""), counts.getOrDefault(m.getStudentId(), 0))).toList();
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

    private String newCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private ClassView view(ClassEntity c) {
        return new ClassView(c.getId().toString(), c.getName(), c.getInviteCode(), members.countByClassId(c.getId()));
    }
}
