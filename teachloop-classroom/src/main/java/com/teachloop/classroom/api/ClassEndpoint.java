package com.teachloop.classroom.api;

import com.teachloop.classroom.domain.*;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.shared.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/classes")
public class ClassEndpoint {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final ClassRepository classes;
    private final ClassMemberRepository members;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ClassEndpoint(ClassRepository classes, ClassMemberRepository members, Clock clock) {
        this.classes = classes;
        this.members = members;
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

    @GetMapping("/{id}/members")
    public List<String> memberIds(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        requireOwned(id, me);
        return members.findByClassId(id).stream().map(m -> m.getStudentId().toString()).toList();
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
        // 越权一律 404，不泄漏存在性
        if (!c.getTeacherId().equals(me.id()) && !me.is("ADMIN")) {
            throw ApiException.notFound("班级不存在");
        }
        return c;
    }

    private static void requireTeacher(CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能创建班级");
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
