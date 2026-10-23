package com.teachloop.identity.ai;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 管理员 AI 配置（保存后需重启应用生效；Key 永不回显）。 */
@RestController
@RequestMapping("/api/admin/ai-settings")
public class AiSettingsEndpoint {

    private final AiSettingsService service;

    public AiSettingsEndpoint(AiSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public AiSettingsService.View get(@RequestAttribute("currentUser") CurrentUser me) {
        requireAdmin(me);
        return service.view();
    }

    @PutMapping
    public AiSettingsService.View update(@RequestAttribute("currentUser") CurrentUser me,
                                         @RequestBody AiSettingsService.UpdateRequest body) {
        requireAdmin(me);
        return service.update(me.id(), body);
    }

    @PostMapping("/test")
    public AiSettingsService.TestResult test(@RequestAttribute("currentUser") CurrentUser me,
                                             @RequestBody AiSettingsService.TestRequest body) {
        requireAdmin(me);
        return service.test(body);
    }

    @PostMapping("/models")
    public AiSettingsService.ModelsResult models(@RequestAttribute("currentUser") CurrentUser me,
                                                 @RequestBody AiSettingsService.TestRequest body) {
        requireAdmin(me);
        return service.listModels(body);
    }

    private static void requireAdmin(CurrentUser me) {
        if (!me.is("ADMIN")) {
            throw ApiException.forbidden("需要管理员权限");
        }
    }
}
