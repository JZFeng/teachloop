package com.teachloop.identity.ai;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

/**
 * 全局当前模型（决策 010，2026-10-24 修订）。
 *
 * <p>v1 只有一份全局模型，**只有 ADMIN 能切换**（`/admin/ai` 每个模型行的「设为当前」）。
 * 教师页不再有 AI 卡片，教师也不接触 provider 地址、Key 与模型选择。
 * {@code GET} 仍对教师开放，仅用于顶栏「AI 未配置」提示判断当前模型是否存在。
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
        requireAdmin(me);
        if (body == null || body.providerId() == null || body.providerId().isBlank()) {
            throw ApiException.badRequest("error.ai.providerId");
        }
        return service.select(me.id(), body.providerId().strip(), body.model());
    }

    /** 切换当前模型属于管理员操作：教师调用一律 403，避免任何教师改掉全站的批改模型。 */
    private static void requireAdmin(CurrentUser me) {
        if (me == null) {
            throw ApiException.unauthorized("error.UNAUTHORIZED");
        }
        if (!me.is("ADMIN")) {
            throw ApiException.forbidden("error.ai.adminOnly");
        }
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
