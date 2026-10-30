package com.teachloop.identity.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.shared.security.CurrentUser;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * T561 / AC-93.1：资料补全过滤器不再 403，只写请求属性。
 *
 * <p>这里是纯过滤器单测（不起 Spring、不连库）：{@link UserRepository} 用 mock，
 * 请求用 spring-test 的 Mock 实现，链路用 lambda 记录「是否放行」。
 */
class ProfileCompletionFilterTest {

    private final UserRepository users = mock(UserRepository.class);
    private final ProfileCompletionFilter filter = new ProfileCompletionFilter(users);

    @Test
    @DisplayName("AC-93.1 缺手机号的教师访问普通接口：放行（不再 403），属性 profileIncomplete=true")
    void passesThroughAndMarksIncomplete() throws Exception {
        User teacher = user(UserRole.TEACHER, "teacher@teachloop.local", null);
        when(users.findById(teacher.getId())).thenReturn(Optional.of(teacher));
        MockHttpServletRequest request = authenticated(teacher, "GET", "/api/papers");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertThat(chained).as("普通请求必须放行（AC-93.1）").isTrue();
        assertThat(response.getStatus()).as("过滤器不再自己写 403").isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(request.getAttribute(ProfileCompletion.ATTR)).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("资料齐全的教师：放行且属性 profileIncomplete=false")
    void completesProfileMarksComplete() throws Exception {
        User teacher = user(UserRole.TEACHER, "teacher@teachloop.local", "13800000000");
        when(users.findById(teacher.getId())).thenReturn(Optional.of(teacher));
        MockHttpServletRequest request = authenticated(teacher, "POST", "/api/classes");
        AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> chained.set(true));

        assertThat(chained).isTrue();
        assertThat(request.getAttribute(ProfileCompletion.ATTR)).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("管理员豁免：没有手机号也不算资料不完整（T034 原口径）")
    void adminIsExempt() throws Exception {
        User admin = user(UserRole.ADMIN, "admin@teachloop.local", null);
        when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
        MockHttpServletRequest request = authenticated(admin, "GET", "/api/papers");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(request.getAttribute(ProfileCompletion.ATTR)).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("判定同源：缺邮箱也算不完整（与缺手机号同一段逻辑）")
    void missingEmailCountsAsIncomplete() throws Exception {
        User teacher = user(UserRole.TEACHER, null, "13800000000");
        when(users.findById(teacher.getId())).thenReturn(Optional.of(teacher));
        MockHttpServletRequest request = authenticated(teacher, "GET", "/api/papers");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(request.getAttribute(ProfileCompletion.ATTR)).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("未登录请求不查库、不写属性（登录接口本身不能被资料状态影响）")
    void anonymousRequestsAreUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> chained.set(true));

        assertThat(chained).isTrue();
        assertThat(request.getAttribute(ProfileCompletion.ATTR)).isNull();
        verify(users, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("CORS 预检不查库（OPTIONS 不写属性）")
    void optionsSkipsLookup() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/classes");
        request.setAttribute("currentUser", new CurrentUser(UUID.randomUUID(), "TEACHER", "老师"));

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(request.getAttribute(ProfileCompletion.ATTR)).isNull();
        verify(users, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    private static MockHttpServletRequest authenticated(User user, String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setAttribute("currentUser", new CurrentUser(user.getId(), user.getRole().name(), user.getDisplayName()));
        return request;
    }

    private static User user(UserRole role, String email, String phone) {
        User user = new User(UUID.randomUUID(), role, email, "x", "示例", Instant.now());
        user.setPhone(phone);
        return user;
    }
}
