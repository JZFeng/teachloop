package com.teachloop.grading.api;

import com.teachloop.grading.domain.Review;
import com.teachloop.grading.domain.ReviewRepository;
import com.teachloop.grading.domain.ReviewStatus;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 复核工作台后端（US-11 / T100–T104，D-15）。
 *
 * <p>老师对 AI 草稿的处理：原样通过（approved）／改到满意（edited）／弃用重批（rejected）；
 * 只有 approved 或 edited 能发布，已发布的内容必须先撤回才能修改。
 *
 * <p>AI 只是可选的辅助（D-15）：任何已交卷提交都可以直接人工判分——复核行不存在时本接口懒建一行
 * {@code original_subjective = null} 的 PENDING 复核，表示「AI 未参与」。没有 AI 草稿时，
 * 未填主观分不能通过（要求老师先给分），但驳回不受限。
 *
 * <p>编辑只允许改主观分与三段文本：客观分由 sitting 在交卷时按标准答案判定，本接口不提供修改入口。
 * 改主观分经 {@link Ports.GradingOutcome#apply} 回写 submission 的总分，阅卷模块不直接改 sitting 的表。
 *
 * <p>详情里的 objectivePoints / subjectivePoints 取自 {@link Ports.SittingLedger#scores}
 * （T243 新增端口），是提交**当前**的实际分，而不是 AI 草稿。
 */
@RestController
@RequestMapping("/api")
public class ReviewEndpoint {

    private static final Logger log = LoggerFactory.getLogger(ReviewEndpoint.class);

    private final ReviewRepository reviews;
    private final Ports.SittingLedger ledger;
    private final Ports.GradingOutcome outcome;
    private final Ports.NotificationPort notifications;
    private final AuditPort audit;
    private final Clock clock;

    public ReviewEndpoint(ReviewRepository reviews, Ports.SittingLedger ledger,
                          Ports.GradingOutcome outcome, Ports.NotificationPort notifications,
                          AuditPort audit, Clock clock) {
        this.reviews = reviews;
        this.ledger = ledger;
        this.outcome = outcome;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    /** T100：三态结论；驳回原因可写在 comment（或 reason，兼容旧前端）。 */
    public record Decision(@NotNull ReviewStatus status, String comment, String reason) {
        String effectiveComment() {
            return reason != null && !reason.isBlank() ? reason : comment;
        }
    }

    /** T101：只改主观分与文本；全部字段可省略，省略即保持不变/清空按 PUT 语义。 */
    public record EditRequest(BigDecimal subjectivePoints, String comment,
                              String strengths, String suggestions) {
    }

    /** T104：批量通过。 */
    public record BulkApproveRequest(List<UUID> submissionIds) {
    }

    public record BulkApproveResult(int approved, int skipped, int total) {
    }

    public record ReviewView(String id, String submissionId, String status, String teacherId,
                             String comment, String strengths, String suggestions,
                             BigDecimal originalSubjective, boolean published,
                             String publishedAt, String createdAt) {
    }

    public record AnswerView(String itemId, int number, String answer) {
    }

    public record ItemView(int number, String questionType, String stem, String options,
                           String answerKey, BigDecimal points, String scoringStrategy,
                           int sectionPosition, String sectionTitle) {
    }

    /**
     * 复核详情：正文、题项与作答、AI 主观草稿、复核字段。
     *
     * @param objectivePoints  提交当前的客观分（sitting 判定，不可手改）
     * @param subjectivePoints 提交当前的主观分（AI 草稿或老师已填的人工分）
     */
    public record ReviewDetail(String submissionId, String studentId, String studentName,
                               String inputKind, String contentText, String contentHtml,
                               List<AnswerView> answers, List<ItemView> items,
                               String rubricMarkdown, String feedbackLanguage,
                               BigDecimal objectivePoints, BigDecimal subjectivePoints,
                               BigDecimal aiSubjectiveDraft, ReviewView review) {
    }

    /**
     * T100：取复核行；不存在则懒建一行「AI 未参与」的待复核（T243 / D-15）。
     *
     * <p>{@code original_subjective = null} 是「AI 没给过主观草稿」的记录方式：老师直接在批改详情里
     * 逐份人工判分时走这条路径，不必先派发 AI。返回的实体未被事务外改写，调用方按需保存。
     */
    private Review reviewOrCreate(UUID submissionId) {
        return reviews.findBySubmissionId(submissionId).orElseGet(() -> reviews.save(
                new Review(UUID.randomUUID(), submissionId, null, ReviewStatus.PENDING, null, null,
                        Instant.now(clock))));
    }

    /**
     * T243：判分前的统一闸门——取提交当前分数，草稿未交卷（submittedAt 为空）不允许判分。
     *
     * <p>未交卷就建复核行会让「待复核队列」混入还在作答的提交，所以先拦下来。
     */
    private Ports.SubmissionScores scoresOf(UUID submissionId) {
        Ports.SubmissionScores scores = ledger.scores(submissionId);
        if (scores.submittedAt() == null) {
            throw ApiException.conflict("error.review.notSubmitted");
        }
        return scores;
    }

    @GetMapping("/grading/queue")
    public List<ReviewView> queue(@RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        return reviews.findByStatusOrderByCreatedAtAsc(ReviewStatus.PENDING).stream()
                .map(ReviewEndpoint::view).toList();
    }

    /** T243：任何已交卷提交都能打开复核详情；没有复核行时懒建（AI 未参与）。 */
    @GetMapping("/submissions/{submissionId}/review")
    public ReviewDetail get(@PathVariable UUID submissionId,
                            @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        Ports.SubmissionScores scores = scoresOf(submissionId);
        Review review = reviewOrCreate(submissionId);
        Ports.WorkItem item = ledger.load(submissionId);
        List<AnswerView> answers = item.answers().stream()
                .map(a -> new AnswerView(str(a.itemId()), a.number(), a.answer()))
                .toList();
        List<ItemView> items = item.items().stream()
                .map(i -> new ItemView(i.number(), i.questionType(), i.stem(), i.options(),
                        i.answerKey(), i.points(), i.scoringStrategy(),
                        i.sectionPosition(), i.sectionTitle()))
                .toList();
        return new ReviewDetail(str(item.submissionId()), str(item.studentId()),
                item.studentName(), item.inputKind(), item.contentText(), item.contentHtml(), answers, items,
                item.rubricMarkdown(), item.feedbackLanguage(),
                scores.objectiveTotal(), scores.subjectiveTotal(),
                review.getOriginalSubjective(), view(review));
    }

    /** 兼容旧调用：三态结论。REJECTED 的原因写 comment；已发布必须先撤回。 */
    @PostMapping("/submissions/{submissionId}/review")
    @Transactional
    public ReviewView decide(@PathVariable UUID submissionId, @RequestBody @Valid Decision body,
                             @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        if (body.status() == ReviewStatus.PENDING) {
            throw ApiException.badRequest("复核结论只能是通过、改后通过或弃用重批");
        }
        scoresOf(submissionId);
        Review r = reviewOrCreate(submissionId);
        if (r.isPublished()) {
            throw ApiException.conflict("已发布，需先撤回才能修改复核结论");
        }
        // T243 / D-15：AI 没参与判分（无草稿）且老师也没填过分，不能「通过」——否则等于给 0 分放行。
        if (body.status() == ReviewStatus.APPROVED
                && r.getOriginalSubjective() == null && r.getTeacherId() == null) {
            throw ApiException.badRequest("error.review.noScore");
        }
        String comment = body.effectiveComment();
        r.decide(body.status(), me.id(), comment);
        reviews.save(r);
        audit.record(new AuditEvent(me.id(), "review:" + body.status().name().toLowerCase(),
                "submission", submissionId.toString(), Map.of("hasComment", comment != null)));
        return view(r);
    }

    /** T101：编辑主观分与文本；审计记录 AI 草稿→最终分与三段文本的 diff。 */
    @PutMapping("/submissions/{submissionId}/review")
    @Transactional
    public ReviewView edit(@PathVariable UUID submissionId, @RequestBody @Valid EditRequest body,
                           @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        Ports.SubmissionScores scores = scoresOf(submissionId);
        Review r = reviewOrCreate(submissionId);
        if (r.isPublished()) {
            throw ApiException.conflict("已发布，需先撤回才能修改");
        }
        if (r.getStatus() != ReviewStatus.PENDING && r.getStatus() != ReviewStatus.EDITED) {
            throw ApiException.conflict("当前复核状态不可编辑，请先重新批改");
        }
        if (body.subjectivePoints() != null && body.subjectivePoints().signum() < 0) {
            throw ApiException.badRequest("主观分不能为负");
        }

        // 变更前快照必须在 r.edit(...) 之前取，否则 Hibernate 托管实体已被改写。
        // T243：beforeSubjective 取提交当前的实际主观分（人工分也在此列），AI 草稿单独记录。
        BigDecimal beforeSubjective = scores.subjectiveTotal();
        BigDecimal aiSubjective = r.getOriginalSubjective();
        String beforeComment = r.getComment();
        String beforeStrengths = r.getStrengths();
        String beforeSuggestions = r.getSuggestions();

        if (body.subjectivePoints() != null) {
            // 经端口回写总分：objectivePoints=null 表示不动客观分（客观分不可手改）。
            outcome.apply(submissionId, new Ports.GradingResult(null, body.subjectivePoints(),
                    List.of(), null, List.of()));
        }
        r.edit(me.id(), body.comment(), body.strengths(), body.suggestions());
        reviews.save(r);

        audit.record(new AuditEvent(me.id(), "review:edited", "submission", submissionId.toString(),
                editDetail(beforeSubjective, body, aiSubjective, beforeComment, beforeStrengths,
                        beforeSuggestions)));
        return view(r);
    }

    /** T103：发布；仅 approved / edited 可发布，重复发布 409。 */
    @PostMapping("/submissions/{submissionId}/review/publish")
    @Transactional
    public ReviewView publish(@PathVariable UUID submissionId,
                              @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        Review r = reviews.findBySubmissionId(submissionId)
                .orElseThrow(() -> ApiException.notFound("这份提交还没有复核记录"));
        if (r.isPublished()) {
            throw ApiException.conflict("这份复核已经发布");
        }
        if (r.getStatus() != ReviewStatus.APPROVED && r.getStatus() != ReviewStatus.EDITED) {
            throw ApiException.conflict("仅已批准或改后通过的复核可以发布");
        }
        r.publish(Instant.now(clock));
        reviews.save(r);
        audit.record(AuditEvent.of(me.id(), "review:publish", "submission", submissionId.toString()));
        notifyPublished(submissionId);
        return view(r);
    }

    /** T114：逐份反馈发布后给学生一条站内通知；通知失败不影响发布。 */
    private void notifyPublished(UUID submissionId) {
        try {
            Ports.WorkItem item = ledger.load(submissionId);
            boolean en = "en".equalsIgnoreCase(item.feedbackLanguage());
            notifications.notify(List.of(new Ports.NotificationMessage(item.studentId(),
                    "feedback_published", "submission", submissionId.toString(),
                    en ? "Feedback published" : "反馈已发布",
                    en ? "Your teacher published the feedback for this submission."
                        : "老师已发布这份提交的反馈，可以查看得分与评语。")));
        } catch (RuntimeException ex) {
            log.warn("review publish notification failed for {}: {}", submissionId, ex.getMessage());
        }
    }

    /** T103：撤回；未发布 409。撤回后状态保留（edited 仍可再次发布）。 */
    @PostMapping("/submissions/{submissionId}/review/withdraw")
    @Transactional
    public ReviewView withdraw(@PathVariable UUID submissionId,
                               @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        Review r = reviews.findBySubmissionId(submissionId)
                .orElseThrow(() -> ApiException.notFound("这份提交还没有复核记录"));
        if (!r.isPublished()) {
            throw ApiException.conflict("这份复核尚未发布，无需撤回");
        }
        r.withdraw();
        reviews.save(r);
        audit.record(AuditEvent.of(me.id(), "review:withdraw", "submission", submissionId.toString()));
        return view(r);
    }

    /**
     * T104：批量通过（只处理 PENDING 的复核）。
     *
     * <p>逐份独立保存与审计；无复核记录、详情不可读（提交不存在/端口抛错）或已决策的提交计入 skipped，
     * 不整体失败。T243：AI 没参与判分（无草稿）且老师还没填分的复核也跳过——批量通过会把这类提交
     * 当成 0 分放行，而它们本应等老师人工给分。
     *
     * <p>AC-11.8 的「篇幅判定边界敏感项默认跳过」当前无法实现：sitting 尚未通过端口暴露
     * {@code submission_metrics.boundary_sensitive}，此处保留注释，等端口补齐后再加跳过条件。
     */
    @PostMapping("/grading/bulk-approve")
    public BulkApproveResult bulkApprove(@RequestBody BulkApproveRequest body,
                                         @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        List<UUID> ids = body == null || body.submissionIds() == null ? List.of() : body.submissionIds();
        int approved = 0;
        int skipped = 0;
        for (UUID submissionId : ids) {
            try {
                Review r = reviews.findBySubmissionId(submissionId).orElse(null);
                if (r == null || r.isPublished() || r.getStatus() != ReviewStatus.PENDING
                        || (r.getOriginalSubjective() == null && r.getTeacherId() == null)) {
                    skipped++;
                    continue;
                }
                ledger.load(submissionId); // 详情不可读的提交不能盲批
                r.decide(ReviewStatus.APPROVED, me.id(), r.getComment());
                reviews.save(r);
                audit.record(new AuditEvent(me.id(), "review:approved", "submission",
                        submissionId.toString(), Map.of("bulk", true)));
                approved++;
            } catch (RuntimeException ex) {
                skipped++;
                log.warn("批量通过跳过 submission={} : {}", submissionId, ex.getMessage());
            }
        }
        return new BulkApproveResult(approved, skipped, ids.size());
    }

    private static void requireStaff(CurrentUser me) {
        if (me == null) {
            throw ApiException.unauthorized("请先登录");
        }
        if (!me.is("TEACHER") && !me.is("ADMIN")) {
            throw ApiException.forbidden("只有教师或管理员可以复核");
        }
    }

    private static ReviewView view(Review r) {
        return new ReviewView(r.getId().toString(), r.getSubmissionId().toString(), r.getStatus().name(),
                r.getTeacherId() == null ? null : r.getTeacherId().toString(),
                r.getComment(), r.getStrengths(), r.getSuggestions(), r.getOriginalSubjective(),
                r.isPublished(), r.getPublishedAt() == null ? null : r.getPublishedAt().toString(),
                r.getCreatedAt().toString());
    }

    /**
     * 审计 detail：提交改前的实际分与 AI 草稿、before/after 主观分 + 三段文本的逐行 diff。
     *
     * @param beforeSubjective 提交当前的实际主观分（人工重判时是上一次的人工分）
     * @param aiSubjective     AI 主观草稿；null 表示 AI 未参与，本轮是纯人工判分
     */
    private static Map<String, Object> editDetail(BigDecimal beforeSubjective, EditRequest body,
                                                  BigDecimal aiSubjective, String beforeComment,
                                                  String beforeStrengths, String beforeSuggestions) {
        Map<String, Object> detail = new LinkedHashMap<>();
        BigDecimal afterSubjective = body.subjectivePoints() != null
                ? body.subjectivePoints() : beforeSubjective;
        detail.put("manual", aiSubjective == null);
        detail.put("aiSubjectiveDraft", aiSubjective);
        detail.put("beforeSubjective", beforeSubjective);
        detail.put("afterSubjective", afterSubjective);
        detail.put("scoreChanged", body.subjectivePoints() != null
                && (beforeSubjective == null || body.subjectivePoints().compareTo(beforeSubjective) != 0));
        detail.put("beforeComment", beforeComment);
        detail.put("afterComment", body.comment());
        detail.put("beforeStrengths", beforeStrengths);
        detail.put("afterStrengths", body.strengths());
        detail.put("beforeSuggestions", beforeSuggestions);
        detail.put("afterSuggestions", body.suggestions());
        String diff = lineDiff("comment", beforeComment, body.comment())
                + lineDiff("strengths", beforeStrengths, body.strengths())
                + lineDiff("suggestions", beforeSuggestions, body.suggestions());
        detail.put("textDiff", diff.isBlank() ? null : diff.strip());
        return detail;
    }

    /** 逐行 diff（LCS），供审计还原教师改了什么文本。 */
    private static String lineDiff(String label, String before, String after) {
        String oldText = before == null ? "" : before;
        String newText = after == null ? "" : after;
        if (oldText.equals(newText)) {
            return "";
        }
        List<String> oldLines = splitLines(oldText);
        List<String> newLines = splitLines(newText);
        int[][] lcs = new int[oldLines.size() + 1][newLines.size() + 1];
        for (int i = oldLines.size() - 1; i >= 0; i--) {
            for (int j = newLines.size() - 1; j >= 0; j--) {
                lcs[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        StringBuilder out = new StringBuilder(label).append(":\n");
        int i = 0;
        int j = 0;
        while (i < oldLines.size() && j < newLines.size()) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.append("- ").append(oldLines.get(i++)).append('\n');
            } else {
                out.append("+ ").append(newLines.get(j++)).append('\n');
            }
        }
        while (i < oldLines.size()) {
            out.append("- ").append(oldLines.get(i++)).append('\n');
        }
        while (j < newLines.size()) {
            out.append("+ ").append(newLines.get(j++)).append('\n');
        }
        return out.toString();
    }

    private static List<String> splitLines(String text) {
        return text.isEmpty() ? List.of() : new ArrayList<>(List.of(text.split("\\R", -1)));
    }

    private static String str(UUID value) {
        return value == null ? null : value.toString();
    }
}
