package com.teachloop.vocab.feedback;

import com.teachloop.shared.error.ApiException;
import com.teachloop.vocab.feedback.FeedbackRepository.FeedbackRow;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端「单词反馈」收件箱：学生从查词页提交的 {@code vocab_feedback} 只有这一个读入口。
 *
 * <p>背景（2026-10-31）：写入口 `POST /api/vocab/words/{id}/feedback`
 * （{@code VocabularyWordDetailEndpoint:188}）从落地起就没有任何读取方，提交后**没人看得到**。
 * 用户已定：开放给<b>管理员</b>（不是学生、也不是教师）。
 *
 * <p><b>权限</b>：整条前缀 `/api/admin/**` 在 {@code SecurityConfig} 里已经收口成
 * {@code hasRole("ADMIN")}，教师/学生打进来由框架回 403，这里**不重复写角色判断**
 * （与 {@code AdminBookEndpoint} 同一口径，避免两套判权）。
 *
 * <p><b>越界禁令</b>：提交人信息只有 `user_id`——`users` 表属 identity 模块，
 * 这里不做跨模块 JOIN，也不为了好看去拼邮箱/姓名；前端展示短 id。
 */
@RestController
@RequestMapping("/api/admin/vocab/feedback")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class AdminFeedbackEndpoint {

    /** 反馈状态，与 V044 里 `vocab_feedback.status` 的 check 约束一一对应。 */
    private static final Set<String> STATUSES = Set.of("OPEN", "RESOLVED", "IGNORED");
    /** 列表过滤值：多一个 `ALL` = 不过滤；缺省按 OPEN（收件箱默认只看待处理）。 */
    private static final Set<String> FILTERS = Set.of("OPEN", "RESOLVED", "IGNORED", "ALL");
    private static final String ALL = "ALL";
    private static final int MAX_PAGE_SIZE = 200;

    private final FeedbackRepository feedback;

    public AdminFeedbackEndpoint(FeedbackRepository feedback) {
        this.feedback = feedback;
    }

    public record FeedbackView(long id, String wordForm, String wordLanguage, String note,
                               String status, UUID submitterId, Instant createdAt) {
    }

    public record FeedbackPage(List<FeedbackView> items, long total, int page, int size) {
    }

    /** 只收 status 一个字段：处理动作就是「这条反馈结束了 / 不处理 / 重开」。 */
    public record StatusRequest(String status) {
    }

    /**
     * 列表：`status` 缺省 OPEN，`ALL` 看全部；按 `created_at desc`（与索引
     * `idx_vocab_feedback_status(status, created_at)` 同向）。
     */
    @GetMapping
    public FeedbackPage list(@RequestParam(required = false) String status,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int size) {
        String filter = parseFilter(status);
        if (page < 1) {
            throw ApiException.badRequest("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("size must be 1.." + MAX_PAGE_SIZE);
        }
        String query = ALL.equals(filter) ? null : filter;
        List<FeedbackView> items = feedback.page(query, (page - 1) * size, size).stream()
                .map(this::view)
                .toList();
        return new FeedbackPage(items, feedback.count(query), page, size);
    }

    /**
     * 处理一条反馈：`OPEN` / `RESOLVED` / `IGNORED`，非法值 400、行不存在 404。
     *
     * <p>返回改完之后的那一行（重新查一遍），前端不用自己猜状态是不是真的落库了。
     */
    @PostMapping("/{id}/status")
    public FeedbackView updateStatus(@PathVariable long id,
                                     @RequestBody(required = false) StatusRequest request) {
        String status = request == null ? null : parseStatus(request.status());
        if (status == null) {
            throw ApiException.badRequest("status must be one of " + STATUSES);
        }
        if (feedback.updateStatus(id, status) == 0) {
            throw ApiException.notFound("feedback not found");
        }
        return feedback.find(id).map(this::view).orElseThrow(
                () -> ApiException.notFound("feedback not found"));
    }

    /** 缺省 OPEN；大小写宽松（前端筛选值与枚举一致，手敲 `all` 也认）。未知值 400。 */
    private static String parseFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return "OPEN";
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        if (!FILTERS.contains(value)) {
            throw ApiException.badRequest("status must be one of " + FILTERS);
        }
        return value;
    }

    /** 写动作只认三种真实状态（不接受 `ALL`）。 */
    private static String parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        return STATUSES.contains(value) ? value : null;
    }

    private FeedbackView view(FeedbackRow row) {
        return new FeedbackView(row.id(), row.wordForm(), row.wordLanguage(), row.note(),
                row.status(), row.submitterId(), row.createdAt());
    }
}
