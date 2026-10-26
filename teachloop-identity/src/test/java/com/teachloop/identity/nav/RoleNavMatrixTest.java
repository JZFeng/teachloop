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
    void adminSeesOnlyClassesAndAi() {
        assertThat(RoleNavMatrix.keysFor(UserRole.ADMIN))
                .containsExactlyInAnyOrder(RoleNavMatrix.NAV_CLASSES, RoleNavMatrix.NAV_AI);
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
                .doesNotContain(RoleNavMatrix.NAV_AI, RoleNavMatrix.NAV_FEEDBACK);
    }

    @Test
    void studentSeesFiveIncludingVocabButNoTeacherItems() {
        assertThat(RoleNavMatrix.keysFor(UserRole.STUDENT))
                .hasSize(5)
                .contains(RoleNavMatrix.NAV_HOME, RoleNavMatrix.NAV_CLASSES,
                        RoleNavMatrix.NAV_FEEDBACK, RoleNavMatrix.NAV_VOCAB_LIBRARY,
                        RoleNavMatrix.NAV_VOCAB_REVIEW)
                .doesNotContain(RoleNavMatrix.NAV_PAPERS, RoleNavMatrix.NAV_GRADING,
                        RoleNavMatrix.NAV_REVIEW, RoleNavMatrix.NAV_REPORTS,
                        RoleNavMatrix.NAV_ASSIGNMENTS, RoleNavMatrix.NAV_AI);
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
        assertThat(RoleNavMatrix.isVocabKey(RoleNavMatrix.NAV_REPORTS)).isFalse();
        assertThat(RoleNavMatrix.isVocabKey(null)).isFalse();
    }
}
