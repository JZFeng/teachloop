package com.teachloop.identity.profile;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.web.csrf.CsrfToken;

/** T033：SPA 在登录前取一次 CSRF token；Cookie 由 CookieCsrfTokenRepository 写入。 */
@RestController
public class CsrfEndpoint {

    @GetMapping("/api/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
}
