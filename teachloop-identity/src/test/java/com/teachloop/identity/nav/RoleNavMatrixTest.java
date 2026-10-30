package com.teachloop.identity.nav;

import com.teachloop.identity.domain.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 007-role-nav / T475：矩阵是唯一真源，这里锁住三套角色集合的边界，
 * 以及「任何被授予的 key 都必须在 ALL_KEYS 里」这条不变量。
 */
class RoleNavMatrixTest {

    @Test
    void adminSeesAiAndVocabBooks() {
        // 2026-10-27：班级移出管理员矩阵——班级只属于师生，管理员不再有查看入口。
        // 2026-10-30（013）：管理员新增「词书管理」入口；它受 vocab 模块开关约束。
        assertThat(RoleNavMatrix.keysFor(UserRole.ADMIN))
                .containsExactlyInAnyOrder(RoleNavMatrix.NAV_AI, RoleNavMatrix.NAV_VOCAB_BOOKS)
                .doesNotContain(RoleNavMatrix.NAV_CLASSES);
    }

    @Test
    void teacherSeesNineIncludingVocabButNoAiOrFeedback() {
        assertThat(RoleNavMatrix.keysFor(UserRole.TEACHER))
                .hasSize(9)
                .contains(RoleNavMatrix.NAV_HOME, RoleNavMatrix.NAV_CLASSES,
                        RoleNavMatrix.NAV_ASSIGNMENTS, RoleNavMatrix.NAV_PAPERS,
                        RoleNavMatrix.NAV_GRADING, RoleNavMatrix.NAV_REVIEW,
                        RoleNavMatrix.NAV_REPORTS, RoleNavMatrix.NAV_VOCAB_SETS,
                        RoleNavMatrix.NAV_VOCAB_ASSIGN)
                .doesNotContain(RoleNavMatrix.NAV_AI);
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
                        RoleNavMatrix.NAV_VOCAB_SETS, RoleNavMatrix.NAV_VOCAB_ASSIGN);
    }

    @Test
    void teachersDoNotGetStudentOnlyVocabEntries() {
        // 我的词库 / 我的词单 / 今日复习是学生专属，教师侧栏不能多出这三项。
        assertThat(RoleNavMatrix.keysFor(UserRole.TEACHER))
                .doesNotContain(RoleNavMatrix.NAV_VOCAB_LIBRARY,
                        RoleNavMatrix.NAV_VOCAB_LISTS, RoleNavMatrix.NAV_VOCAB_REVIEW);
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
        assertThat(RoleNavMatrix.isVocabKey(null)).isFalse();
    }
}
