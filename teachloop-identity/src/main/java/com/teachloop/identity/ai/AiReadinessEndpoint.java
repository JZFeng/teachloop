package com.teachloop.identity.ai;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 就绪状态：教师/管理员可读，学生 403（学生永远不直接调 AI）。
 *
 * <p>前端在「让 AI 先批 / 全部交给 AI 批改 / 重试失败 / 生成错题解析 / 重试背单词内容」这些
 * 按钮上先用它判断要不要提示「请联系管理员设置 AI」。字段形状是前后端约定，不要增减：
 * {@code configured, engine, ready, source, provider, model}。
 *
 * <p>provider / model 只做展示，绝不回显 Key。
 */
@RestController
@RequestMapping("/api/ai-readiness")
public class AiReadinessEndpoint {

    public record View(boolean configured, String engine, boolean ready, String source,
                       String provider, String model) {

        static View of(Ports.AiReadiness readiness) {
            return new View(readiness.configured(), readiness.engine(), readiness.ready(),
                    readiness.source(), readiness.provider(), readiness.model());
        }
    }

    private final AiReadinessService service;

    public AiReadinessEndpoint(AiReadinessService service) {
        this.service = service;
    }

    @GetMapping
    public View view(@RequestAttribute("currentUser") CurrentUser me) {
        if (me == null) {
            throw ApiException.unauthorized("error.UNAUTHORIZED");
        }
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("error.FORBIDDEN");
        }
        return View.of(service.readiness());
    }
}
