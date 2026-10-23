package com.teachloop.identity.ai;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

/**
 * D-16：教师/管理员在个人资料页维护自己的 DeepSeek Key。
 *
 * <p>GET 永远只回掩码；PUT 保存密文；POST /test 用临时值或已保存值测试连接。
 * 学生没有 AI 判分职责，访问直接 403。
 */
@RestController
@RequestMapping("/api/profile/ai-settings")
public class ProfileAiSettingsEndpoint {

    private final AiSettingsService service;

    public ProfileAiSettingsEndpoint(AiSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public AiSettingsService.UserView get(@RequestAttribute("currentUser") CurrentUser me) {
        requireTeacher(me);
        return service.userView(me.id());
    }

    @PutMapping
    public AiSettingsService.UserView update(@RequestAttribute("currentUser") CurrentUser me,
                                             @RequestBody AiSettingsService.UpdateRequest body) {
        requireTeacher(me);
        return service.updateUser(me.id(), me.id(), body);
    }

    @PostMapping("/test")
    public AiSettingsService.TestResult test(@RequestAttribute("currentUser") CurrentUser me,
                                             @RequestBody AiSettingsService.TestRequest body) {
        requireTeacher(me);
        return service.testUser(me.id(), body);
    }

    /** 模型发现：从教师填写的 base URL 拉 /v1/models；失败不影响手填。 */
    @PostMapping("/models")
    public AiSettingsService.ModelsResult models(@RequestAttribute("currentUser") CurrentUser me,
                                                 @RequestBody AiSettingsService.TestRequest body) {
        requireTeacher(me);
        return service.listModels(me.id(), body);
    }

    private static void requireTeacher(CurrentUser me) {
        if (me == null) {
            throw ApiException.unauthorized("请先登录");
        }
        if (!me.is("TEACHER") && !me.is("ADMIN")) {
            throw ApiException.forbidden("只有教师或管理员可以配置 AI Key");
        }
    }
}
