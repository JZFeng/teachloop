package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.admin.AdminService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 2026-10-28：CSRF 由属性 {@code teachloop.security.csrf} 控制，不再写死「prod profile」。
 *
 * <p>为什么要这条测试：
 * <ul>
 *   <li>旧实现是 {@code SecurityConfig} 里的 {@code acceptsProfiles("prod")}，qa 打不开 CSRF，
 *       于是「第一次写请求 403」这类生产专属问题只能在线上撞见；</li>
 *   <li>同时 {@code application-prod.yml} 里的 {@code teachloop.security.csrf: true} 是一行
 *       <b>没人读的死配置</b>，改它没有任何效果。</li>
 * </ul>
 *
 * <p>这里在<b>不带任何 profile</b> 的情况下把属性打开，断言已登录写请求缺 token 被拦——
 * qa（{@code application-qa.yml}）走的就是这条路径。
 *
 * <p>注意 401/403 的区别：<b>匿名</b>请求缺 CSRF 会走 authenticationEntryPoint，返回 401
 * （不是 403）；只有<b>已登录</b>请求才是 accessDeniedHandler 的 403。断言放在登录之后，
 * 否则测不到 CSRF 本身。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"teachloop.security.csrf=true", "teachloop.grading.engine=stub"})
@Testcontainers
class CsrfPropertyTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.seed.enabled", () -> "true");
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired
    Environment env;

    @Autowired
    AdminService adminService;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("属性打开（无 prod profile）：已登录写请求缺 token 403，带 token 放行")
    void csrfFollowsPropertyNotProfile() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        adminService.createAdmin("csrf-prop-admin@teachloop.local", "csrf-prop-pass");

        // 与前端 csrfHeaders() 同一条路径：GET /api/csrf 拿 cookie，再把 cookie 原值回传为 header
        HttpResponse<String> tokenRes = client.send(HttpRequest.newBuilder(URI.create(base + "/api/csrf"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(tokenRes.statusCode()).isEqualTo(200);
        String csrfCookie = tokenRes.headers().allValues("set-cookie").stream()
                .map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow();
        String csrfToken = csrfCookie.substring("XSRF-TOKEN=".length());

        HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("Cookie", csrfCookie)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"csrf-prop-admin@teachloop.local\",\"password\":\"csrf-prop-pass\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String session = login.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();

        HttpResponse<String> missing = client.send(HttpRequest.newBuilder(URI.create(base + "/api/admin/teacher-invites"))
                .header("Cookie", session)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(403);

        HttpResponse<String> withToken = client.send(HttpRequest.newBuilder(URI.create(base + "/api/admin/teacher-invites"))
                .header("Cookie", session + "; " + csrfCookie)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(withToken.statusCode()).isEqualTo(200);
    }
}
