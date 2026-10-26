package com.teachloop.identity.ai;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 管理员维护全局 provider 列表（决策 010 修订）。 */
@RestController
@RequestMapping("/api/admin/ai-providers")
public class AiProviderEndpoint {

    private final AiProviderService service;
    private final AiSettingsService aiSettings;

    public AiProviderEndpoint(AiProviderService service, AiSettingsService aiSettings) {
        this.service = service;
        this.aiSettings = aiSettings;
    }

    /** 决策 022：是否处于故障转移、从哪条切来、什么时候、为什么（不含密钥）。 */
    @GetMapping("/status")
    public AiSettingsService.FailoverState status(@RequestAttribute("currentUser") CurrentUser me) {
        requireAdmin(me);
        return aiSettings.failoverState();
    }

    /** 决策 022 D1：调整故障转移顺序（数值小者优先）。 */
    @PutMapping("/{id}/priority")
    public List<AiProviderService.View> priority(@RequestAttribute("currentUser") CurrentUser me,
                                                 @PathVariable UUID id,
                                                 @RequestBody PriorityRequest body) {
        requireAdmin(me);
        return service.reorder(me.id(), id, body.priority());
    }

    public record PriorityRequest(int priority) {
    }

    @GetMapping
    public List<AiProviderService.View> list(@RequestAttribute("currentUser") CurrentUser me) {
        requireAdmin(me);
        return service.list();
    }

    @PostMapping
    public AiProviderService.View create(@RequestAttribute("currentUser") CurrentUser me,
                                         @RequestBody AiProviderService.Request body) {
        requireAdmin(me);
        return service.create(me.id(), body);
    }

    @PutMapping("/{id}")
    public AiProviderService.View update(@RequestAttribute("currentUser") CurrentUser me,
                                         @PathVariable UUID id,
                                         @RequestBody AiProviderService.Request body) {
        requireAdmin(me);
        return service.update(me.id(), id, body);
    }

    /** 把某个 provider 设为默认（active_provider）；只切 provider，不要求再选模型。 */
    @PutMapping("/{id}/default")
    public List<AiProviderService.View> setDefault(@RequestAttribute("currentUser") CurrentUser me,
                                                   @PathVariable UUID id) {
        requireAdmin(me);
        return service.setDefault(me.id(), id);
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestAttribute("currentUser") CurrentUser me, @PathVariable UUID id) {
        requireAdmin(me);
        service.delete(me.id(), id);
    }

    @PostMapping("/models")
    public AiSettingsService.ModelsResult models(@RequestAttribute("currentUser") CurrentUser me,
                                                 @RequestBody AiSettingsService.TestRequest body) {
        requireAdmin(me);
        return service.models(body);
    }

    @PostMapping("/test")
    public AiSettingsService.TestResult test(@RequestAttribute("currentUser") CurrentUser me,
                                             @RequestBody AiSettingsService.TestRequest body) {
        requireAdmin(me);
        return service.test(body);
    }

    private static void requireAdmin(CurrentUser me) {
        if (me == null) {
            throw ApiException.unauthorized("error.UNAUTHORIZED");
        }
        if (!me.is("ADMIN")) {
            throw ApiException.forbidden("error.ai.adminOnly");
        }
    }
}
