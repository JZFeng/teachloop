package com.teachloop.identity.nav;

import com.teachloop.identity.domain.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 007-role-nav / T475：矩阵是唯一真源，这里锁住三套角色集合的边界，
 * 以及「任何被授予的 key 都必须在 ALL_KEYS 里」这条不变量。
 *
 * <p>2026-10-30（AC-3.13 / AC-3.14）改了两处判断，都在本文件里留了原因：
 * 教师保留学习入口；管理员不含任何学习功能（角色是职责，不是能力包）。
 */
class RoleNavMatrixTest {

    @Test
    void adminSeesTeachersAiAndVocabBooks() {
        // 2026-10-27：班级移出管理员矩阵——班级只属于师生，管理员不再有查看入口。
        // 2026-10-30（013）：管理员新增「词书管理」入口；它受 vocab 模块开关约束。
        // 2026-10-30（AC-3.10）：管理员新增「教师管理」入口（发邀请码）。
        assertThat(RoleNavMatrix.keysFor(UserRole.ADMIN))
                .containsExactlyInAnyOrder(RoleNavMatrix.NAV_TEACHERS, RoleNavMatrix.NAV_AI,
                        RoleNavMatrix.NAV_VOCAB_BOOKS)
                .doesNotContain(RoleNavMatrix.NAV_CLASSES);
    }

    @Test
    void adminHasNoLearningFeatures() {
        // AC-3.14（2026-10-30 用户定，回归断言）：角色是职责，不是能力包。
        // 管理员要背单词只能另注册一个学生账号，矩阵里不放任何 nav.vocab 学习入口。
        // 注意 NAV_VOCAB_BOOKS 是"词书维护"（管理员工作），不是学习入口，所以单独放行。
        assertThat(RoleNavMatrix.keysFor(UserRole.ADMIN))
                .doesNotContain(RoleNavMatrix.NAV_VOCAB_LIBRARY, RoleNavMatrix.NAV_VOCAB_LISTS,
                        RoleNavMatrix.NAV_VOCAB_REVIEW, RoleNavMatrix.NAV_VOCAB_SETS,
                        RoleNavMatrix.NAV_VOCAB_ASSIGN)
                .doesNotContain(RoleNavMatrix.NAV_HOME, RoleNavMatrix.NAV_ASSIGNMENTS,
                        RoleNavMatrix.NAV_PAPERS, RoleNavMatrix.NAV_GRADING,
                        RoleNavMatrix.NAV_REVIEW, RoleNavMatrix.NAV_REPORTS);
    }

    @Test
    void teacherSeesTwelveIncludingLearningEntriesButNoAi() {
        // 2026-10-30（AC-3.13）：9 → 12。教师原先只有 sets/assign（发词），
        // 于是学生升级成老师后侧栏的「背单词」会消失；学习数据本来就按 user_id 挂着，
        // 入口不该随角色消失——背单词属于人人可用的功能层。
        assertThat(RoleNavMatrix.keysFor(UserRole.TEACHER))
                .hasSize(12)
                .contains(RoleNavMatrix.NAV_HOME, RoleNavMatrix.NAV_CLASSES,
                        RoleNavMatrix.NAV_ASSIGNMENTS, RoleNavMatrix.NAV_PAPERS,
                        RoleNavMatrix.NAV_GRADING, RoleNavMatrix.NAV_REVIEW,
                        RoleNavMatrix.NAV_REPORTS, RoleNavMatrix.NAV_VOCAB_SETS,
                        RoleNavMatrix.NAV_VOCAB_ASSIGN,
                        RoleNavMatrix.NAV_VOCAB_LIBRARY, RoleNavMatrix.NAV_VOCAB_LISTS,
                        RoleNavMatrix.NAV_VOCAB_REVIEW)
                .doesNotContain(RoleNavMatrix.NAV_AI, RoleNavMatrix.NAV_TEACHERS);
    }

    @Test
    void teacherKeepsLearningEntriesAfterUpgrade() {
        // 这条原先叫 teachersDoNotGetStudentOnlyVocabEntries，断言的是"教师不能有这三项"。
        // 2026-10-30 用户明确否掉了那个假设：我的词库 / 我的词单 / 今日复习不是"学生专属"，
        // 而是学习功能，教师升级上来以后必须还在（AC-3.13）。
        assertThat(RoleNavMatrix.keysFor(UserRole.TEACHER))
                .contains(RoleNavMatrix.NAV_VOCAB_LIBRARY,
                        RoleNavMatrix.NAV_VOCAB_LISTS, RoleNavMatrix.NAV_VOCAB_REVIEW);
    }

    @Test
    void studentSeesFiveIncludingVocabButNoTeacherItems() {
        // 2026-10-26（AC-72.8）：学生 5 → 4 项，「成绩与评语」菜单项随列表页一起删除。
        // 2026-10-26（AC-69.11 修订）：再加「我的词单」→ 4 → 5 项（vocab 开时）。
        assertThat(RoleNavMatrix.keysFor(UserRole.STUDENT))
                .hasSize(5)
                .contains(RoleNavMatrix.NAV_HOME, RoleNavMatrix.NAV_CLASSES,
                        RoleNavMatrix.NAV_VOCAB_LIBRARY, RoleNavMatrix.NAV_VOCAB_LISTS,
                        RoleNavMatrix.NAV_VOCAB_REVIEW)
                .doesNotContain(RoleNavMatrix.NAV_PAPERS, RoleNavMatrix.NAV_GRADING,
                        RoleNavMatrix.NAV_REVIEW, RoleNavMatrix.NAV_REPORTS,
                        RoleNavMatrix.NAV_ASSIGNMENTS, RoleNavMatrix.NAV_AI,
                        RoleNavMatrix.NAV_TEACHERS,
                        RoleNavMatrix.NAV_VOCAB_SETS, RoleNavMatrix.NAV_VOCAB_ASSIGN);
    }

    @Test
    void allKeysAreUniqueAndCoverEveryGrantedKey() {
        assertThat(RoleNavMatrix.allKeys()).doesNotHaveDuplicates();
        for (UserRole role : UserRole.values()) {
            assertThat(RoleNavMatrix.allKeys())
                    .as("role %s grants a key that is missing from ALL_KEYS", role)
                    .containsAll(RoleNavMatrix.keysFor(role));
        }
    }

    @Test
    void nullRoleGetsNothing() {
        assertThat(RoleNavMatrix.keysFor(null)).isEmpty();
    }

    @Test
    void onlyVocabKeysAreModuleGated() {
        assertThat(RoleNavMatrix.isVocabKey(RoleNavMatrix.NAV_VOCAB_SETS)).isTrue();
        assertThat(RoleNavMatrix.isVocabKey(RoleNavMatrix.NAV_VOCAB_LIBRARY)).isTrue();
        assertThat(RoleNavMatrix.isVocabKey(RoleNavMatrix.NAV_VOCAB_LISTS)).isTrue();
        assertThat(RoleNavMatrix.isVocabKey(RoleNavMatrix.NAV_REPORTS)).isFalse();
        // 教师管理不是 vocab 开关管的，否则关掉 vocab 模块会连带关掉发邀请码。
        assertThat(RoleNavMatrix.isVocabKey(RoleNavMatrix.NAV_TEACHERS)).isFalse();
        assertThat(RoleNavMatrix.isVocabKey(null)).isFalse();
    }
}
