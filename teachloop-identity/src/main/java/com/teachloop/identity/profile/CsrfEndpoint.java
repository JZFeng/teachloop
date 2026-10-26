package com.teachloop.identity.profile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T033：SPA 在登录前取一次 CSRF token；Cookie 由 CookieCsrfTokenRepository 写入。
 *
 * <p>2026-10-25 部署实测修正：不再依赖「CsrfFilter 把 token 放进 request attribute」——
 * prod（Spring Security 7）下那个 attribute 取到的是 null，接口返回空 token，
 * 于是浏览器所有 POST 都是 401（连首个管理员都建不了）。
 * 现在直接由仓库生成 + 写 Cookie，结果确定。
 */
@RestController
public class CsrfEndpoint {

    /** 与 SecurityConfig 同一份配置（stateless，按 Cookie 读写）。 */
    private final CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();

    @GetMapping("/api/csrf")
    public Map<String, String> csrf(HttpServletRequest request, HttpServletResponse response) {
        // 每次取都生成新 token 并写回 Cookie：不依赖过滤器内部状态，结果确定。
        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, response);
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
}
