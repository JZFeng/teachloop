package com.teachloop.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.shared.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 007-role-nav / T476：内容按<b>当前用户</b>算，不再是全局常量。
 * 同时保留 T389 的契约：vocab 开关值如实透出。
 */
class FeaturesEndpointTest {

    private static CurrentUser user(String role) {
        return new CurrentUser(UUID.randomUUID(), role, role);
    }

    @Test
    @DisplayName("管理员：只有班级与 AI 设置")
    void adminSeesOnlyClassesAndAi() {
        Map<String, Boolean> f = new FeaturesEndpoint(false).features(user("ADMIN"));

        assertThat(f)
                .containsEntry("nav.classes", true)
                .containsEntry("nav.ai", true)
                .containsEntry("nav.home", false)
                .containsEntry("nav.reports", false)
                .containsEntry("nav.papers", false)
                .containsEntry("nav.grading", false)
                .containsEntry("nav.review", false)
                .containsEntry("nav.assignments", false);
    }

    @Test
    @DisplayName("教师：能看到报表与批改，看不到 AI 设置")
    void teacherSeesTeacherItems() {
        Map<String, Boolean> f = new FeaturesEndpoint(false).features(user("TEACHER"));

        assertThat(f)
                .containsEntry("nav.reports", true)
                .containsEntry("nav.grading", true)
                .containsEntry("nav.review", true)
                .containsEntry("nav.papers", true)
                .containsEntry("nav.ai", false)
                .doesNotContainKey("nav.feedback"); // 2026-10-26 AC-72.8：名录里已无此项
    }

    @Test
    @DisplayName("学生：看不到反馈菜单（列表页已删）、试卷与批改")
    void studentSeesStudentItems() {
        Map<String, Boolean> f = new FeaturesEndpoint(false).features(user("STUDENT"));

        assertThat(f)
                .doesNotContainKey("nav.feedback")
                .containsEntry("nav.home", true)
                .containsEntry("nav.papers", false)
                .containsEntry("nav.grading", false)
                .containsEntry("nav.reports", false);
    }

    @Test
    @DisplayName("vocab 类 key 跟随模块开关，其余 key 不受影响")
    void vocabKeysFollowModuleSwitch() {
        Map<String, Boolean> off = new FeaturesEndpoint(false).features(user("TEACHER"));
        Map<String, Boolean> on = new FeaturesEndpoint(true).features(user("TEACHER"));

        assertThat(off).containsEntry("nav.vocab.sets", false).containsEntry("vocab", false);
        assertThat(on).containsEntry("nav.vocab.sets", true).containsEntry("vocab", true);
        assertThat(off).containsEntry("nav.reports", true);
    }

    @Test
    @DisplayName("未知角色不放行任何菜单")
    void unknownRoleGetsNothing() {
        Map<String, Boolean> f = new FeaturesEndpoint(true).features(user("WHATEVER"));

        // vocab 是模块开关、与角色无关；角色维度只看 nav.*
        assertThat(f.entrySet().stream()
                .filter(e -> e.getKey().startsWith("nav."))
                .map(Map.Entry::getValue))
                .allMatch(v -> !v);
    }
}
