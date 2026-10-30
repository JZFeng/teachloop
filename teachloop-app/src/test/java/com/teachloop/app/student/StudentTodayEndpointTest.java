package com.teachloop.app.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 011 / T559 / AC-94.1 / 94.2 / 94.3：今日页聚合端点的纯单元测试。
 *
 * <p>不起 Spring：三段时间源都是端口接口，直接给替身就能验证「排序 / 截断边界 / 单段降级 /
 * 非学生空值」这些本类自己的逻辑。真实 SQL、权限与序列化由 app 的集成测试覆盖。
 */
class StudentTodayEndpointTest {

    private static final Instant NOW = Instant.parse("2026-10-30T18:00:00Z");
    private static final UUID STUDENT = UUID.fromString("00000000-0000-0000-0000-000000000090");
    private static final CurrentUser STUDENT_USER = new CurrentUser(STUDENT, "STUDENT", "学生");

    private static ObjectProvider<Ports.ReviewSlicePort> provider(Ports.ReviewSlicePort port) {
        return new ObjectProvider<>() {
            @Override
            public Ports.ReviewSlicePort getObject(Object... args) {
                return port;
            }

            @Override
            public Ports.ReviewSlicePort getIfAvailable() {
                return port;
            }

            @Override
            public Ports.ReviewSlicePort getIfUnique() {
                return port;
            }
        };
    }

    /** 可配置替身：每段可以选择抛异常，用来验证降级互不影响。 */
    private static final class Stubs implements Ports.AgendaPort, Ports.ClassMembership, Ports.UserZonePort {

        List<Ports.AssignmentRow> pending = List.of();
        List<Ports.ExamRow> upcoming = List.of();
        RuntimeException pendingError;
        RuntimeException examError;
        List<UUID> classIds = List.of(UUID.randomUUID());
        String storedZone;

        @Override
        public List<Ports.AssignmentRow> pending(UUID studentId) {
            if (pendingError != null) {
                throw pendingError;
            }
            return pending;
        }

        @Override
        public List<Ports.ExamRow> upcoming(UUID studentId, Instant from, Instant to) {
            if (examError != null) {
                throw examError;
            }
            return upcoming;
        }

        @Override
        public List<UUID> classIdsOf(UUID studentId) {
            return classIds;
        }

        @Override
        public List<UUID> membersOf(UUID classId) {
            return List.of();
        }

        @Override
        public Set<UUID> classIdsOwnedBy(Collection<UUID> ids, UUID teacherId) {
            return Set.of();
        }

        @Override
        public Map<UUID, String> classNames(Collection<UUID> ids) {
            return Map.of();
        }

        @Override
        public Optional<Ports.UserZone> of(UUID userId) {
            return Optional.ofNullable(storedZone).map(Ports.UserZone::new);
        }
    }

    private static StudentTodayEndpoint endpoint(Stubs stubs, Ports.ReviewSlicePort review) {
        return new StudentTodayEndpoint(provider(review), stubs, stubs, stubs,
                Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Shanghai");
    }

    private static Ports.ReviewSlice sampleSlice() {
        return new Ports.ReviewSlice(24, 8, 10, 20);
    }

    // ─────────────────────────────────────────────── AC-94.1 响应三段齐全

    @Test
    @DisplayName("AC-94.1：三段字段齐全，review 四个数、作业五个字段、partial 三格")
    void responseHasAllSections() {
        Stubs stubs = new Stubs();
        UUID classId = UUID.randomUUID();
        stubs.classIds = List.of(classId);
        UUID offeringId = UUID.randomUUID();
        stubs.pending = List.of(new Ports.AssignmentRow(offeringId, "Unit 5", "初二（3）班",
                Instant.parse("2026-10-31T10:00:00Z"), "todo"));
        stubs.upcoming = List.of(new Ports.ExamRow(UUID.randomUUID(), "期中听说", "初二（3）班",
                Instant.parse("2026-11-02T01:00:00Z")));

        StudentTodayEndpoint.TodayView view = endpoint(stubs, (userId, iana) -> sampleSlice())
                .today(STUDENT_USER, null);

        assertThat(view.review()).isEqualTo(new StudentTodayEndpoint.ReviewView(24, 8, 10, 20));
        assertThat(view.assignments()).hasSize(1);
        assertThat(view.assignments().get(0).id()).isEqualTo(offeringId);
        assertThat(view.assignments().get(0).title()).isEqualTo("Unit 5");
        assertThat(view.assignments().get(0).className()).isEqualTo("初二（3）班");
        assertThat(view.assignments().get(0).dueAt()).isEqualTo(Instant.parse("2026-10-31T10:00:00Z"));
        assertThat(view.assignments().get(0).status()).isEqualTo("todo");
        assertThat(view.exams()).hasSize(1);
        assertThat(view.exams().get(0).title()).isEqualTo("期中听说");
        assertThat(view.partial()).isEqualTo(new StudentTodayEndpoint.PartialView("ok", "ok", "ok"));
        assertThat(view.inClass()).isTrue();
        assertThat(view.zone()).isEqualTo("Asia/Shanghai");
        assertThat(view.defaultZone()).isEqualTo("Asia/Shanghai");
    }

    // ─────────────────────────────────────────────── AC-90.2 排序

    @Test
    @DisplayName("AC-90.2：逾期在前（逾期更久的更前），其余按 dueAt 升序")
    void assignmentsSortOverdueFirstThenDueAscending() {
        Stubs stubs = new Stubs();
        Instant yesterday = NOW.minusSeconds(86_400);
        Instant twoDaysAgo = NOW.minusSeconds(2 * 86_400);
        Instant tomorrow = NOW.plusSeconds(86_400);
        Instant nextWeek = NOW.plusSeconds(7 * 86_400);
        stubs.pending = new ArrayList<>(List.of(
                row("later", nextWeek),
                row("future", tomorrow),
                row("overdue-new", yesterday),
                row("overdue-old", twoDaysAgo)));

        var ordered = StudentTodayEndpoint.sortAssignments(stubs.pending, NOW);

        assertThat(ordered).extracting(StudentTodayEndpoint.AssignmentView::title)
                .containsExactly("overdue-old", "overdue-new", "future", "later");
    }

    @Test
    @DisplayName("AC-90.2 边界：不截断（全部待办返回，卡片只显示 3 条是前端规则）")
    void assignmentsAreNotTruncated() {
        Stubs stubs = new Stubs();
        List<Ports.AssignmentRow> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("a" + i, NOW.plusSeconds(i * 3600L)));
        }
        stubs.pending = rows;

        var view = endpoint(stubs, (userId, iana) -> sampleSlice()).today(STUDENT_USER, null);
        assertThat(view.assignments()).hasSize(5);
    }

    // ─────────────────────────────────────────────── AC-94.2 单段降级

    @Test
    @DisplayName("AC-94.2：作业段失败 → 该段空 + partial.assignments=error，其余段正常")
    void assignmentsFailureIsIsolated() {
        Stubs stubs = new Stubs();
        stubs.pendingError = new IllegalStateException("db down");
        stubs.upcoming = List.of(new Ports.ExamRow(UUID.randomUUID(), "考试", "班",
                Instant.parse("2026-11-02T01:00:00Z")));

        var view = endpoint(stubs, (userId, iana) -> sampleSlice()).today(STUDENT_USER, null);

        assertThat(view.assignments()).isEmpty();
        assertThat(view.exams()).hasSize(1);
        assertThat(view.review().due()).isEqualTo(24);
        assertThat(view.partial()).isEqualTo(new StudentTodayEndpoint.PartialView("ok", "error", "ok"));
    }

    @Test
    @DisplayName("AC-94.2：复习段失败 → review 归零 + partial.review=error，作业段不受影响")
    void reviewFailureIsIsolated() {
        Stubs stubs = new Stubs();
        stubs.pending = List.of(row("作业", NOW.plusSeconds(3600)));

        var view = endpoint(stubs, (userId, iana) -> {
            throw new IllegalStateException("vocab down");
        }).today(STUDENT_USER, null);

        assertThat(view.review()).isEqualTo(StudentTodayEndpoint.ReviewView.empty());
        assertThat(view.assignments()).hasSize(1);
        assertThat(view.partial()).isEqualTo(new StudentTodayEndpoint.PartialView("error", "ok", "ok"));
    }

    @Test
    @DisplayName("AC-94.2：考试段失败 → 该段空 + partial.exams=error")
    void examsFailureIsIsolated() {
        Stubs stubs = new Stubs();
        stubs.examError = new IllegalStateException("boom");

        var view = endpoint(stubs, (userId, iana) -> sampleSlice()).today(STUDENT_USER, null);

        assertThat(view.exams()).isEmpty();
        assertThat(view.partial()).isEqualTo(new StudentTodayEndpoint.PartialView("ok", "ok", "error"));
        assertThat(view.review().due()).isEqualTo(24);
    }

    @Test
    @DisplayName("vocab 关闭（没有 ReviewSlicePort 实现）时按零值返回，不报错")
    void vocabDisabledMeansZeroReview() {
        Stubs stubs = new Stubs();
        var view = endpoint(stubs, null).today(STUDENT_USER, null);
        assertThat(view.review()).isEqualTo(StudentTodayEndpoint.ReviewView.empty());
        assertThat(view.partial().review()).isEqualTo("ok");
    }

    // ─────────────────────────────────────────────── AC-94.3 非学生

    @Test
    @DisplayName("AC-94.3：教师/管理员返回三段空值，不调用任何取数端口")
    void nonStudentGetsEmptySections() {
        Stubs stubs = new Stubs();
        stubs.pending = List.of(row("不该被读到", NOW));
        StudentTodayEndpoint endpoint = endpoint(stubs, (userId, iana) -> sampleSlice());

        for (String role : List.of("TEACHER", "ADMIN")) {
            var view = endpoint.today(new CurrentUser(UUID.randomUUID(), role, role), null);
            assertThat(view.review()).isEqualTo(StudentTodayEndpoint.ReviewView.empty());
            assertThat(view.assignments()).isEmpty();
            assertThat(view.exams()).isEmpty();
            assertThat(view.partial()).isEqualTo(new StudentTodayEndpoint.PartialView("ok", "ok", "ok"));
            assertThat(view.inClass()).isFalse();
        }
    }

    // ─────────────────────────────────────────────── AC-90.5 / AC-99.1

    @Test
    @DisplayName("AC-90.5：没有任何班级 → inClass=false（前端据此换成邀请码卡）")
    void inClassFalseWhenNoMembership() {
        Stubs stubs = new Stubs();
        stubs.classIds = List.of();
        var view = endpoint(stubs, (userId, iana) -> sampleSlice()).today(STUDENT_USER, null);
        assertThat(view.inClass()).isFalse();
    }

    @Test
    @DisplayName("AC-99.1：X-Timezone 优先于资料；非法头回落到资料；都没有则产品默认")
    void zoneFollowsFallbackChain() {
        Stubs stubs = new Stubs();
        stubs.storedZone = "Europe/London";
        StudentTodayEndpoint endpoint = endpoint(stubs, (userId, iana) -> sampleSlice());

        assertThat(endpoint.today(STUDENT_USER, "America/Los_Angeles").zone())
                .isEqualTo("America/Los_Angeles");
        assertThat(endpoint.today(STUDENT_USER, null).zone()).isEqualTo("Europe/London");
        assertThat(endpoint.today(STUDENT_USER, "Mars/Base").zone()).isEqualTo("Europe/London");

        stubs.storedZone = "Not/AZone";
        assertThat(endpoint.today(STUDENT_USER, null).zone()).isEqualTo("Asia/Shanghai");
        assertThat(endpoint.today(STUDENT_USER, "").zone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    @DisplayName("AC-99.1：端口调用方拿到的是解析后的 IANA 名（复习切片按它算日界）")
    void reviewSliceReceivesResolvedZone() {
        Stubs stubs = new Stubs();
        stubs.storedZone = "America/Los_Angeles";
        List<String> seen = new ArrayList<>();
        endpoint(stubs, (userId, iana) -> {
            seen.add(iana);
            return sampleSlice();
        }).today(STUDENT_USER, null);

        assertThat(seen).containsExactly("America/Los_Angeles");
    }

    private static Ports.AssignmentRow row(String title, Instant dueAt) {
        return new Ports.AssignmentRow(UUID.randomUUID(), title, "班", dueAt, "todo");
    }
}
