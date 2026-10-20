package com.teachloop.identity.auth;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.error.ErrorCode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * T031：微信 code 换 openid；同一 openid 复用同一账号。
 * 未配置 appid/secret 时返回 503（不伪造登录）。
 */
@Component
public class WeChatAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(WeChatAuthProvider.class);

    private final UserRepository users;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String appId;
    private final String secret;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    public WeChatAuthProvider(UserRepository users, ObjectMapper mapper, Clock clock,
                              @Value("${teachloop.wechat.app-id:}") String appId,
                              @Value("${teachloop.wechat.secret:}") String secret,
                              @Value("${teachloop.wechat.base-url:https://api.weixin.qq.com}") String baseUrl) {
        this.users = users;
        this.mapper = mapper;
        this.clock = clock;
        this.appId = appId;
        this.secret = secret;
        this.baseUrl = baseUrl;
    }

    public boolean configured() {
        return appId != null && !appId.isBlank() && secret != null && !secret.isBlank();
    }

    public Optional<String> openIdFor(String code) {
        if (!configured()) {
            throw new ApiException(ErrorCode.UNAVAILABLE, "微信登录未配置");
        }
        try {
            String url = baseUrl + "/sns/jscode2session?appid=" + enc(appId) + "&secret=" + enc(secret)
                    + "&js_code=" + enc(code) + "&grant_type=authorization_code";
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return Optional.empty();
            }
            var json = mapper.readTree(res.body());
            if (json.has("errcode") && json.get("errcode").asInt() != 0) {
                log.warn("wechat code exchange failed: errcode={}", json.get("errcode").asInt());
                return Optional.empty();
            }
            String openId = json.has("openid") ? json.get("openid").asString() : null;
            return openId == null || openId.isBlank() ? Optional.empty() : Optional.of(openId);
        } catch (Exception ex) {
            log.warn("wechat code exchange error: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public User loginOrCreate(String code) {
        String openId = openIdFor(code)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "微信授权失败"));
        return users.findByWechatOpenId(openId).orElseGet(() -> {
            User user = new User(UUID.randomUUID(), UserRole.STUDENT, null, null, "微信用户", Instant.now(clock));
            user.setWechatOpenId(openId);
            return users.save(user);
        });
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
