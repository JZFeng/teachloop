package com.teachloop.grading.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.teachloop.grading.ai.LlmCallException;
import com.teachloop.grading.ai.LlmClient;
import com.teachloop.grading.domain.AiCallRepository;
import com.teachloop.grading.domain.ItemExplanationRepository;
import com.teachloop.grading.explanation.ExplanationEngine;
import com.teachloop.grading.explanation.FakeLlmClient;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** T088：选题、跳过已有、跳过无题干、单题失败继续、权限与 limit 夹紧。 */
class ExplanationEndpointTest {

    private static final UUID OFFERING_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final CurrentUser TEACHER =
            new CurrentUser(UUID.fromString("44444444-4444-4444-4444-444444444444"), "TEACHER", "王老师");
    private static final CurrentUser ADMIN =
            new CurrentUser(UUID.fromString("55555555-5555-5555-5555-555555555555"), "ADMIN", "管理员");
    private static final CurrentUser STUDENT =
            new CurrentUser(UUID.fromString("66666666-6666-6666-6666-666666666666"), "STUDENT", "张三");

    private Ports.ExplanationWorkQueue queue;
    private ItemExplanationRepository repo;
    private AiCallRepository aiCalls;
    private Clock clock;

    @BeforeEach
    void setUp() {
        queue = mock(Ports.ExplanationWorkQueue.class);
        repo = mock(ItemExplanationRepository.class);
        aiCalls = mock(AiCallRepository.class);
        clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("生成成功写库；已存在与无题干分别计数；total=取到的错题数")
    void generatesAndSkips() {
        Ports.ExplanationItem fresh = item("第一题?");
        Ports.ExplanationItem blankStem = item("   ");
        Ports.ExplanationItem existing = item("第三题?");
        when(queue.wrongItemsOf(OFFERING_ID, 50)).thenReturn(List.of(fresh, blankStem, existing));
        when(repo.existsByItemIdAndLanguageAndPromptVersion(any(), anyString(), anyString())).thenReturn(false);
        when(repo.existsByItemIdAndLanguageAndPromptVersion(eq(existing.itemId()), anyString(), anyString()))
                .thenReturn(true);
        when(repo.insertIfAbsent(any(), any(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        FakeLlmClient llm = FakeLlmClient.responding(response("因为 B 与题干条件一致。"));
        ExplanationEndpoint endpoint = endpoint(llm);

        ExplanationEndpoint.GenerateResult result = endpoint.generate(OFFERING_ID, 50, TEACHER);

        assertThat(result.generated()).isEqualTo(1);
        assertThat(result.skippedExisting()).isEqualTo(1);
        assertThat(result.skippedNoStem()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.total()).isEqualTo(3);

        ArgumentCaptor<String> language = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> version = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(repo).insertIfAbsent(any(), eq(fresh.itemId()), language.capture(), version.capture(),
                text.capture(), any());
        assertThat(language.getValue()).isEqualTo("zh-CN");
        assertThat(version.getValue()).isEqualTo("explanation-v1");
        assertThat(text.getValue()).isEqualTo("因为 B 与题干条件一致。");
        verify(repo, never()).insertIfAbsent(any(), eq(blankStem.itemId()), anyString(), anyString(),
                anyString(), any());
        verify(repo, never()).insertIfAbsent(any(), eq(existing.itemId()), anyString(), anyString(),
                anyString(), any());

        // 只对真正要生成的那一题调模型，跳过的题零成本。
        assertThat(llm.requests).hasSize(1);
    }

    @Test
    @DisplayName("同一题第二次调用：幂等键已存在，计 skippedExisting，不再调模型")
    void secondCallSkipsExisting() {
        Ports.ExplanationItem item = item("重复生成题?");
        when(queue.wrongItemsOf(OFFERING_ID, 50)).thenReturn(List.of(item));
        when(repo.existsByItemIdAndLanguageAndPromptVersion(any(), anyString(), anyString()))
                .thenReturn(false, true);
        when(repo.insertIfAbsent(any(), any(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        FakeLlmClient llm = FakeLlmClient.responding(response("解析一"), response("解析二"));
        ExplanationEndpoint endpoint = endpoint(llm);

        ExplanationEndpoint.GenerateResult first = endpoint.generate(OFFERING_ID, 50, TEACHER);
        ExplanationEndpoint.GenerateResult second = endpoint.generate(OFFERING_ID, 50, TEACHER);

        assertThat(first.generated()).isEqualTo(1);
        assertThat(second.generated()).isZero();
        assertThat(second.skippedExisting()).isEqualTo(1);
        assertThat(llm.requests).hasSize(1);
        verify(repo, times(1)).insertIfAbsent(any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("模型失败：该题计 failed，不写库，接口不整体失败")
    void countsFailureWithoutWriting() {
        Ports.ExplanationItem item = item("失败题?");
        when(queue.wrongItemsOf(OFFERING_ID, 50)).thenReturn(List.of(item));
        when(repo.existsByItemIdAndLanguageAndPromptVersion(any(), anyString(), anyString())).thenReturn(false);
        FakeLlmClient llm = FakeLlmClient.failing(new LlmCallException("DEEPSEEK_API_KEY 未配置"));
        ExplanationEndpoint endpoint = endpoint(llm);

        ExplanationEndpoint.GenerateResult result = endpoint.generate(OFFERING_ID, 50, TEACHER);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.generated()).isZero();
        assertThat(result.total()).isEqualTo(1);
        verify(repo, never()).insertIfAbsent(any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("并发下另一请求已写入：insert 返回 0 时计 skippedExisting，不重复计 generated")
    void countsConflictAsExisting() {
        Ports.ExplanationItem item = item("并发题?");
        when(queue.wrongItemsOf(OFFERING_ID, 50)).thenReturn(List.of(item));
        when(repo.existsByItemIdAndLanguageAndPromptVersion(any(), anyString(), anyString())).thenReturn(false);
        when(repo.insertIfAbsent(any(), any(), anyString(), anyString(), anyString(), any())).thenReturn(0);
        ExplanationEndpoint endpoint = endpoint(FakeLlmClient.responding(response("解析")));

        ExplanationEndpoint.GenerateResult result = endpoint.generate(OFFERING_ID, 50, TEACHER);

        assertThat(result.generated()).isZero();
        assertThat(result.skippedExisting()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("学生调用返回 403，且不触发任何选题或模型调用")
    void studentIsForbidden() {
        ExplanationEndpoint endpoint = endpoint(FakeLlmClient.responding());

        assertThatThrownBy(() -> endpoint.generate(OFFERING_ID, 50, STUDENT))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code().name()).isEqualTo("FORBIDDEN"));
        verify(queue, never()).wrongItemsOf(any(), anyInt());
    }

    @Test
    @DisplayName("教师与管理员都可用")
    void teacherAndAdminAllowed() {
        when(queue.wrongItemsOf(any(), anyInt())).thenReturn(List.of());
        ExplanationEndpoint endpoint = endpoint(FakeLlmClient.responding());

        assertThat(endpoint.generate(OFFERING_ID, 50, TEACHER).total()).isZero();
        assertThat(endpoint.generate(OFFERING_ID, 50, ADMIN).total()).isZero();
    }

    @Test
    @DisplayName("limit 默认 50，并夹紧到 1..50")
    void clampsLimit() {
        when(queue.wrongItemsOf(any(), anyInt())).thenReturn(List.of());
        ExplanationEndpoint endpoint = endpoint(FakeLlmClient.responding());

        endpoint.generate(OFFERING_ID, null, TEACHER);
        endpoint.generate(OFFERING_ID, 0, TEACHER);
        endpoint.generate(OFFERING_ID, 999, TEACHER);

        verify(queue, times(2)).wrongItemsOf(OFFERING_ID, 50);
        verify(queue, times(1)).wrongItemsOf(OFFERING_ID, 1);
    }

    private ExplanationEndpoint endpoint(FakeLlmClient llm) {
        ExplanationEngine engine = new ExplanationEngine(llm, aiCalls, clock);
        return new ExplanationEndpoint(queue, repo, engine, clock);
    }

    private static Ports.ExplanationItem item(String stem) {
        return new Ports.ExplanationItem(UUID.randomUUID(), 1, "SINGLE_CHOICE", stem,
                "A. one\nB. two", "B", 3, "zh-CN");
    }

    private static LlmClient.LlmResponse response(String text) {
        return new LlmClient.LlmResponse("deepseek-flash", text, 100, 20, 300);
    }
}
