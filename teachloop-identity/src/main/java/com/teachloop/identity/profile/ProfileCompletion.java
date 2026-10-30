package com.teachloop.identity.profile;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRole;

/**
 * T561 / US-93：资料是否完整的**唯一判定**（手机号 + 邮箱都非空；管理员豁免）。
 *
 * <p>2026-10-30 起资料补全改为可跳过（AC-93.1）：{@link ProfileCompletionFilter} 不再返回
 * 403，只把 {@link #isIncomplete(User)} 的结论写进请求属性 {@link #ATTR}；
 * 需要强制的写操作（建班、发词，见 AC-93.4）读这个属性自己拒。
 *
 * <p>所以判定只此一处：过滤器、{@code /api/auth/me}、端点校验都调它，
 * 不存在第二份「手机号 + 邮箱 + 管理员豁免」的代码。
 */
public final class ProfileCompletion {

    /**
     * 请求属性名：资料是否**不完整**（{@link Boolean}）。
     *
     * <p>由 {@link ProfileCompletionFilter} 在已登录请求上写入；属性缺失表示「无从判断」
     * （过滤器不在链上，例如端点单测），调用方按放行处理——真正的强制点在过滤器无豁免地覆盖全部请求。
     * 不与 {@code currentUser}、{@code teachloop.authenticated} 冲突。
     */
    public static final String ATTR = "teachloop.profileIncomplete";

    private ProfileCompletion() {
    }

    /** 管理员豁免：管理员由 CLI/初始化页创建，可能本来就没有手机号（T034 原口径，保持不变）。 */
    public static boolean isIncomplete(User user) {
        if (user == null) {
            return false;
        }
        if (user.getRole() == UserRole.ADMIN) {
            return false;
        }
        return blank(user.getPhone()) || blank(user.getEmail());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
