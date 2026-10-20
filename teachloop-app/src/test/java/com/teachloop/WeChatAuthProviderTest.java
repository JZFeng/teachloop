package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.teachloop.identity.auth.WeChatAuthProvider;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** T031：微信 code 换 openid 的成功/失败/缺 openid 路径，用本地 HTTP 替身。 */
class WeChatAuthProviderTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("同一 openid 复用账号；新 openid 建学生账号")
    void reusesOpenId() throws Exception {
        start("{\"openid\":\"openid-1\"}");
        UserRepository users = mock(UserRepository.class);
        when(users.findByWechatOpenId("openid-1")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        WeChatAuthProvider provider = provider(users);

        User first = provider.loginOrCreate("code-1");
        assertThat(first.getRole()).isEqualTo(UserRole.STUDENT);
        assertThat(first.getWechatOpenId()).isEqualTo("openid-1");

        when(users.findByWechatOpenId("openid-1")).thenReturn(Optional.of(first));
        assertThat(provider.loginOrCreate("code-2").getId()).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("微信返回 errcode 或缺 openid 时授权失败")
    void rejectsInvalidResponse() throws Exception {
        start("{\"errcode\":40029,\"errmsg\":\"invalid code\"}");
        UserRepository users = mock(UserRepository.class);
        WeChatAuthProvider provider = provider(users);
        assertThat(provider.openIdFor("bad")).isEmpty();

        start("{\"session_key\":\"x\"}");
        assertThat(provider("users", users).openIdFor("no-openid")).isEmpty();
    }

    private WeChatAuthProvider provider(UserRepository users) {
        return provider("users", users);
    }

    private WeChatAuthProvider provider(String unused, UserRepository users) {
        return new WeChatAuthProvider(users, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                "appid", "secret", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void start(String json) throws Exception {
        if (server != null) server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sns/jscode2session", exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }
}
