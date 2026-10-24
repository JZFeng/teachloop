package com.teachloop.identity.ai;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

/**
 * 教师 /profile 的全局模型选择（决策 010 修订）。
 *
 * <p>v1 只有一份全局模型：任何教师切换后，全站 AI 批改都用新模型。教师不接触 provider 地址与 Key。
 */
@RestController
@RequestMapping("/api/ai-models")
public class AiModelEndpoint {

    public record SelectRequest(String providerId, String model) {
    }

    private final AiProviderService service;

    public AiModelEndpoint(AiProviderService service) {
        this.service = service;
    }

    @GetMapping
    public AiProviderService.Options options(@RequestAttribute("currentUser") CurrentUser me) {
        requireTeacherOrAdmin(me);
        return service.options();
    }

    @PutMapping("/current")
    public AiProviderService.Options select(@RequestAttribute("currentUser") CurrentUser me,
                                            @RequestBody SelectRequest body) {
        requireTeacherOrAdmin(me);
        if (body == null || body.providerId() == null || body.providerId().isBlank()) {
            throw ApiException.badRequest("error.ai.providerId");
        }
        return service.select(me.id(), body.providerId().strip(), body.model());
    }

    private static void requireTeacherOrAdmin(CurrentUser me) {
        if (me == null) {
            throw ApiException.unauthorized("error.UNAUTHORIZED");
        }
        if (!me.is("TEACHER") && !me.is("ADMIN")) {
            throw ApiException.forbidden("error.ai.teacherOnly");
        }
    }
}
