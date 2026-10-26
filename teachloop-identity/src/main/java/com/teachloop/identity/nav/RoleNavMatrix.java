package com.teachloop.identity.nav;

import com.teachloop.identity.domain.UserRole;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色 × 导航菜单矩阵（007-role-nav）：谁能看到哪些菜单项的<b>唯一真源</b>。
 *
 * <p>前端只保留一份「菜单目录」（key → 路径 / 图标 / 文案 / 全局顺序），
 * 是否显示由 {@code GET /api/features} 返回的 key 集合决定；前端不做任何角色判断。
 *
 * <p>vocab 类 key（{@code nav.vocab.*}）在这里按「该角色本应有」列出，实际是否生效
 * 还取决于模块开关 {@code teachloop.vocab.enabled}，由 {@code FeaturesEndpoint} 求交。
 */
public final class RoleNavMatrix {

    public static final String NAV_HOME = "nav.home";
    public static final String NAV_CLASSES = "nav.classes";
    public static final String NAV_ASSIGNMENTS = "nav.assignments";
    public static final String NAV_PAPERS = "nav.papers";
    public static final String NAV_VOCAB_SETS = "nav.vocab.sets";
    public static final String NAV_VOCAB_ASSIGN = "nav.vocab.assign";
    public static final String NAV_GRADING = "nav.grading";
    public static final String NAV_REVIEW = "nav.review";
    public static final String NAV_REPORTS = "nav.reports";
    public static final String NAV_VOCAB_LIBRARY = "nav.vocab.library";
    public static final String NAV_VOCAB_REVIEW = "nav.vocab.review";
    public static final String NAV_FEEDBACK = "nav.feedback";
    public static final String NAV_AI = "nav.ai";

    /**
     * 全部已知 key。顺序与前端菜单目录一致：前端按自己的目录顺序过滤，
     * 三套侧栏顺序由此自然得出，不需要按角色分支。
     */
    public static final List<String> ALL_KEYS = List.of(
            NAV_HOME, NAV_CLASSES, NAV_ASSIGNMENTS, NAV_PAPERS,
            NAV_VOCAB_SETS, NAV_VOCAB_ASSIGN, NAV_GRADING, NAV_REVIEW, NAV_REPORTS,
            NAV_VOCAB_LIBRARY, NAV_VOCAB_REVIEW, NAV_FEEDBACK, NAV_AI);

    /**
     * 角色默认可见集合。管理员只做班级查看与 AI 配置（2026-10-24 用户拍板）：
     * 不含首页、作业、试卷、词集、发词、批改、复核、报表。
     */
    private static final Map<UserRole, Set<String>> ROLE_KEYS = Map.of(
            UserRole.ADMIN, Set.of(NAV_CLASSES, NAV_AI),
            UserRole.TEACHER, Set.of(NAV_HOME, NAV_CLASSES, NAV_ASSIGNMENTS, NAV_PAPERS,
                    NAV_VOCAB_SETS, NAV_VOCAB_ASSIGN, NAV_GRADING, NAV_REVIEW, NAV_REPORTS),
            UserRole.STUDENT, Set.of(NAV_HOME, NAV_CLASSES, NAV_VOCAB_LIBRARY, NAV_VOCAB_REVIEW,
                    NAV_FEEDBACK));

    private RoleNavMatrix() {
    }

    /** 该角色默认可见的 key；未知角色返回空集，绝不默认放行。 */
    public static Set<String> keysFor(UserRole role) {
        if (role == null) {
            return Set.of();
        }
        return ROLE_KEYS.getOrDefault(role, Set.of());
    }

    public static List<String> allKeys() {
        return ALL_KEYS;
    }

    /** vocab 类 key 受模块开关控制，其余 key 只受角色矩阵控制。 */
    public static boolean isVocabKey(String key) {
        return key != null && key.startsWith("nav.vocab.");
    }
}
