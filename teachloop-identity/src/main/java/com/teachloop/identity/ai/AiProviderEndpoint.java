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

    public AiProviderEndpoint(AiProviderService service) {
        this.service = service;
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
