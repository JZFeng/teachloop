package com.teachloop.app;

import com.teachloop.identity.domain.UserRole;
import com.teachloop.identity.nav.RoleNavMatrix;
import com.teachloop.shared.security.CurrentUser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * 把「当前用户最终能用的 key」告诉前端（007-role-nav）。
 *
 * <p>本端点同时承载两类信息：
 * <ul>
 *   <li>{@code nav.*}：由后端 {@link RoleNavMatrix} 按角色算出的菜单可见性——前端不再判断角色；</li>
 *   <li>{@code vocab}：模块是否上线，开关关闭时 vocab 路由不注册、导航不出现
 *       （宪法第十五条：不放占位空承诺）。</li>
 * </ul>
 *
 * <p>返回值<b>包含为 false 的 key</b>，前端可以明确区分「不具备」与「字段缺失」。
 * 本端点自身始终存在，否则前端无法区分「关闭」和「接口挂了」。
 */
@RestController
public class FeaturesEndpoint {

    private final boolean vocabEnabled;

    public FeaturesEndpoint(@Value("${teachloop.vocab.enabled:false}") boolean vocabEnabled) {
        this.vocabEnabled = vocabEnabled;
    }

    @GetMapping("/api/features")
    public Map<String, Boolean> features(@RequestAttribute("currentUser") CurrentUser me) {
        Set<String> granted = RoleNavMatrix.keysFor(parseRole(me == null ? null : me.role()));
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String key : RoleNavMatrix.allKeys()) {
            boolean byRole = granted.contains(key);
            boolean byModule = !RoleNavMatrix.isVocabKey(key) || vocabEnabled;
            out.put(key, byRole && byModule);
        }
        // 模块开关本身照旧透出：前端仍用它决定 vocab 路由是否注册。
        out.put("vocab", vocabEnabled);
        return out;
    }

    /** 未知/空角色一律按「无任何菜单」处理，绝不默认放行。 */
    private static UserRole parseRole(String role) {
        if (role == null) {
            return null;
        }
        try {
            return UserRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
