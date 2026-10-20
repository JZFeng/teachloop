package com.teachloop.identity.profile;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** T033：SPA 在登录前取一次 CSRF token；Cookie 由 CookieCsrfTokenRepository 写入。 */
@RestController
public class CsrfEndpoint {

    private final ObjectProvider<CsrfToken> tokenProvider;

    public CsrfEndpoint(ObjectProvider<CsrfToken> tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @GetMapping("/api/csrf")
    public Map<String, String> csrf() {
        // 开发/测试 profile 关闭 CSRF，此时没有 CsrfToken bean；返回空 token 而不是 500。
        CsrfToken token = tokenProvider.getIfAvailable();
        if (token == null) {
            return Map.of("token", "", "headerName", "X-XSRF-TOKEN");
        }
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
}
