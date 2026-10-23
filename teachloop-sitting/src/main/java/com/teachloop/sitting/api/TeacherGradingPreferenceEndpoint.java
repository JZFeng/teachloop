package com.teachloop.sitting.api;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.sitting.service.TeacherGradingPreferenceService;
import org.springframework.web.bind.annotation.*;

/**
 * 教师级作业批改时机（决策 010）。学生没有作业可派发，访问直接 403。
 */
@RestController
@RequestMapping("/api/profile/grading-preferences")
public class TeacherGradingPreferenceEndpoint {

    private final TeacherGradingPreferenceService service;

    public TeacherGradingPreferenceEndpoint(TeacherGradingPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public TeacherGradingPreferenceService.View get(@RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        return service.view(me.id());
    }

    @PutMapping
    public TeacherGradingPreferenceService.View update(
            @RequestAttribute("currentUser") CurrentUser me,
            @RequestBody TeacherGradingPreferenceService.UpdateRequest body) {
        requireTeacher(me);
        return service.update(me.id(), me.id(), body);
    }

    private static void requireTeacher(CurrentUser me) {
        if (me == null) {
            throw ApiException.unauthorized("error.UNAUTHORIZED");
        }
        if (!me.is("TEACHER")) {
            throw ApiException.forbidden("error.teacherOnly");
        }
    }
}
