package com.teachloop.identity.profile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** T033：SPA 在登录前取一次 CSRF token；Cookie 由 CookieCsrfTokenRepository 写入。 */
@RestController
public class CsrfEndpoint {

    @GetMapping("/api/csrf")
    public Map<String, String> csrf(HttpServletRequest request) {
        // prod：CsrfFilter 把 token 放在 request attribute；调用 getToken() 触发 Cookie 写入。
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            token = (CsrfToken) request.getAttribute("_csrf");
        }
        if (token == null) {
            // 开发/测试 profile 关闭 CSRF，没有 token；返回空而不是 500。
            return Map.of("token", "", "headerName", "X-XSRF-TOKEN");
        }
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
}
