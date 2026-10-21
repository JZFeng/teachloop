package com.teachloop.sitting.api;

import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.text.BlankText;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.sitting.domain.*;
import com.teachloop.sitting.service.ExamSessionService;
import com.teachloop.sitting.service.MaterialGrouper;
import com.teachloop.sitting.service.VideoProbe;
import com.teachloop.sitting.service.VideoRules;
import com.teachloop.sitting.service.OfferingWindows;
import com.teachloop.sitting.service.SnapshotCodec;
import com.teachloop.sitting.service.SubmissionAttachments;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 学生作答侧：考前须知 → 开始 → 自动保存 → 交卷；到点由服务端定稿（T199 / T200）。
 */
@RestController
@RequestMapping("/api/offerings/{offeringId}")
public class ExamSessionEndpoint {

    private static final ZoneId EXAM_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private static final List<String> NOTES = List.of(
            "开始答题后开始计时，考试窗口只管「什么时候可以开始」，已经在进行的考试不会因截止时间到而提前结束。",
            "计时以服务端为准：刷新页面、更换设备或修改本机时间都不会延长或重置剩余时间。",
            "到点后服务端自动交卷，以最后一次自动保存的作答为准；未保存的输入不会被保留。",
            "考试进行中不显示标准答案与解析。");

    private final OfferingRepository offerings;
    private final OfferingClassRepository offeringClasses;
    private final SubmissionRepository submissions;
    private final ObjectiveAnswerRepository answers;
    private final Ports.ReviewFeedback reviewFeedback;
    private final org.springframework.beans.factory.ObjectProvider<Ports.ExplanationCatalog> explanations;
    private final Ports.ClassMembership membership;
    private final SnapshotCodec codec;
    private final OfferingWindows windows;
    private final ExamSessionService sessions;
    private final SubmissionAttachments attachments;
    private final VideoProbe videoProbe;
    private final AuditPort audit;
    private final Clock clock;

    public ExamSessionEndpoint(OfferingRepository offerings, OfferingClassRepository offeringClasses,
                               SubmissionRepository submissions, ObjectiveAnswerRepository answers,
                               Ports.ReviewFeedback reviewFeedback,
                               org.springframework.beans.factory.ObjectProvider<Ports.ExplanationCatalog> explanations,
                               Ports.ClassMembership membership,
                               SnapshotCodec codec, OfferingWindows windows,
                               ExamSessionService sessions, SubmissionAttachments attachments,
                               VideoProbe videoProbe, AuditPort audit, Clock clock) {
        this.offerings = offerings;
        this.offeringClasses = offeringClasses;
        this.submissions = submissions;
        this.answers = answers;
        this.reviewFeedback = reviewFeedback;
        this.explanations = explanations;
        this.membership = membership;
        this.codec = codec;
        this.windows = windows;
        this.sessions = sessions;
        this.attachments = attachments;
        this.videoProbe = videoProbe;
        this.audit = audit;
        this.clock = clock;
    }

    /** 考试入口页（考前须知）：点「开始答题」才起算（T200 / AC-21.16）。 */
    @GetMapping("/entry")
    public SittingDtos.EntryView entry(@PathVariable UUID offeringId,
                                       @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        if (me.is("STUDENT")) {
            requireParticipant(o, me);
        }
        Ports.PaperSnapshot snap = codec.read(o.getPaperSnapshot());
        OfferingWindows.Window window = windows.forStudent(o, me.id());
        ExamSessionService.SessionView s = sessions.session(o, me.id(), me.is("STUDENT"));
        Instant now = Instant.now(clock);
        boolean exam = o.getKind() == OfferingKind.EXAM;

        boolean canStart;
        if (!exam || !me.is("STUDENT") || s.submitted()) {
            canStart = false;
        } else if (o.getTimingMode() == TimingMode.FIXED_WINDOW) {
            canStart = false; // 统一开考没有「开始」按钮，窗口开了就自动计时
        } else {
            canStart = !s.started() && now.isBefore(window.dueAt());
        }

        String notice = notice(o, s, now);
        return new SittingDtos.EntryView(o.getId().toString(), snap.title(), snap.items().size(),
                snap.totalPoints(), window.durationMinutes(), o.getTimingMode().name(), str(o.getStartAt()),
                window.dueAt().toString(), now.toString(), s.started(), canStart, s.remainingSeconds(),
                str(s.deadlineAt()), s.submitted(), s.autoSubmitted(), s.locked(), notice, NOTES,
                o.getRubricMarkdown());
    }

    /**
     * 学生端试卷内容：结构一律来自布置时的快照，**不返回标准答案与解析**（AC-21.9 / T198）。
     */
    @GetMapping("/paper")
    public SittingDtos.ExamPaperView paper(@PathVariable UUID offeringId,
                                           @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        requireStudent(me);
        requireParticipant(o, me);
        Ports.PaperSnapshot snap = codec.read(o.getPaperSnapshot());
        OfferingWindows.Window window = windows.forStudent(o, me.id());
        List<SittingDtos.ExamPaperItemView> items = MaterialGrouper.group(snap.items()).stream()
                .map(g -> new SittingDtos.ExamPaperItemView(g.item().number(), g.item().questionType(),
                        g.item().points(), g.item().stem(), g.item().options(),
                        g.item().sectionPosition(), g.item().sectionTitle(),
                        g.groupKey(), g.material(), g.questionStem(), g.blankIndex()))
                .toList();
        return new SittingDtos.ExamPaperView(o.getId().toString(), snap.title(), snap.totalPoints(),
                window.durationMinutes(), items);
    }

    /**
     * 学生反馈详情（T111 / T112 / T113 / T194）：
     * 自己的分始终可见；错题标准答案、复核评语与班级均分只在反馈发布后返回。
     */
    @GetMapping("/my-feedback")
    public SittingDtos.MyFeedbackView myFeedback(@PathVariable UUID offeringId,
                                                 @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        requireStudent(me);
        requireParticipant(o, me);
        Ports.PaperSnapshot snap = codec.read(o.getPaperSnapshot());
        List<Submission> attempts = submissions.findByOfferingIdAndStudentIdOrderByAttemptNoDesc(
                o.getId(), me.id());
        Submission latest = attempts.stream().filter(Submission::isActive).findFirst()
                .orElse(attempts.isEmpty() ? null : attempts.get(0));
        Submission graded = attempts.stream()
                .filter(x -> x.getSubmittedAt() != null && x.getRejectedAt() == null)
                .findFirst().orElse(null);
        Submission base = graded != null ? graded : latest;

        // 反馈可见性：教师逐份复核发布（reviews.published_at）或整场发布成绩，二者任一即可。
        var note = base == null ? java.util.Optional.<Ports.ReviewNote>empty()
                : reviewFeedback.noteOf(base.getId());
        boolean published = o.isPublishScores()
                || note.map(n -> n.publishedAt() != null).orElse(false);
        List<SittingDtos.FeedbackWrongItem> wrong = List.of();
        SittingDtos.FeedbackReviewNote review = null;
        if (published && base != null && base.getSubmittedAt() != null) {
            Map<Integer, Ports.ItemSpec> specByNumber = new HashMap<>();
            snap.items().forEach(i -> specByNumber.put(i.number(), i));
            wrong = answers.findBySubmissionId(base.getId()).stream()
                    .filter(a -> !a.isCorrect())
                    .filter(a -> {
                        Ports.ItemSpec spec = specByNumber.get(a.getNumber());
                        return spec != null && !"ESSAY".equals(spec.questionType());
                    })
                    .map(a -> {
                        Ports.ItemSpec spec = specByNumber.get(a.getNumber());
                        String explanation = explanations.getIfAvailable() == null ? null
                                : explanations.getIfAvailable()
                                        .explanationOf(spec.itemId(), o.getFeedbackLanguage()).orElse(null);
                        return new SittingDtos.FeedbackWrongItem(a.getNumber(), spec.questionType(),
                                spec.points(), BlankText.forText(spec.stem()), a.getAnswer(),
                                spec.answerKey(), explanation);
                    })
                    .toList();
            review = note
                    .map(n -> new SittingDtos.FeedbackReviewNote(n.status(), n.comment(), n.strengths(),
                            n.suggestions(), n.createdAt()))
                    .orElse(null);
        }

        List<UUID> offeringClassIds = offeringClasses.findByOfferingId(o.getId()).stream()
                .map(OfferingClass::getClassId).toList();
        List<UUID> myClassIds = membership.classIdsOf(me.id()).stream()
                .filter(offeringClassIds::contains).toList();
        Set<UUID> expected = new HashSet<>();
        for (UUID classId : myClassIds) {
            expected.addAll(membership.membersOf(classId));
        }
        Map<UUID, Submission> bestByStudent = new LinkedHashMap<>();
        for (Submission s : submissions.findByOfferingIdOrderByCreatedAtDesc(o.getId())) {
            if (s.getSubmittedAt() == null || s.getRejectedAt() != null) {
                continue;
            }
            Submission current = bestByStudent.get(s.getStudentId());
            if (current == null || s.getAttemptNo() > current.getAttemptNo()) {
                bestByStudent.put(s.getStudentId(), s);
            }
        }
        BigDecimal myScore = base == null ? null
                : base.getObjectiveTotal().add(base.getSubjectiveTotal());
        BigDecimal average = null;
        boolean peersVisible = o.isPublishScores() && o.isShowPeerOverview();
        if (peersVisible && !bestByStudent.isEmpty()) {
            BigDecimal sum = bestByStudent.values().stream()
                    .map(s2 -> s2.getObjectiveTotal().add(s2.getSubjectiveTotal()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            average = sum.divide(BigDecimal.valueOf(bestByStudent.size()), 2, RoundingMode.HALF_UP);
        }
        SittingDtos.FeedbackOverview overview = new SittingDtos.FeedbackOverview(expected.size(),
                bestByStudent.size(), myScore, average, peersVisible);

        String message = !published ? "反馈尚未发布"
                : base == null ? "还没有提交记录" : "反馈已发布";

        return new SittingDtos.MyFeedbackView(o.getId().toString(), snap.title(),
                BigDecimal.valueOf(snap.totalPoints()), published, message,
                base == null ? null : attemptView(base), wrong, review, overview);
    }

    /** 开始答题：起算点落库，返回服务端剩余秒数。 */
    @PostMapping("/start")
    public SittingDtos.SessionView start(@PathVariable UUID offeringId,
                                         @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        requireStudent(me);
        requireParticipant(o, me);
        return sessionDto(o, me.id(), sessions.start(o, me.id()));
    }

    /** T064：已交卷后开新一次作答；旧 attempt 保留但不再 active。 */
    @PostMapping("/attempts")
    public SittingDtos.SessionView newAttempt(@PathVariable UUID offeringId,
                                              @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        requireStudent(me);
        requireParticipant(o, me);
        return sessionDto(o, me.id(), sessions.startNewAttempt(o, me.id()));
    }

    /** 心跳 / 重连：只读，不重置计时（AC-26.7）。 */
    @GetMapping("/session")
    public SittingDtos.SessionView session(@PathVariable UUID offeringId,
                                           @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        requireStudent(me);
        requireParticipant(o, me);
        return sessionDto(o, me.id(), sessions.session(o, me.id(), true));
    }

    /** 自动保存草稿：不交卷，但到点后会被服务端定稿（AC-26.8）。 */
    @PutMapping("/answers")
    public SittingDtos.AnswerSaveResponse autosave(@PathVariable UUID offeringId,
                                                   @RequestBody SittingDtos.SubmitRequest body,
                                                   @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        requireStudent(me);
        requireParticipant(o, me);
        ExamSessionService.SessionView s = sessions.saveDraft(o, me.id(), body.contentHtml(),
                body.contentText(), body.answers());
        Submission sub = s.submission() == null ? null : s.submission();
        return new SittingDtos.AnswerSaveResponse(sub == null ? null : sub.getId().toString(),
                sub == null ? null : sub.getObjectiveTotal(), s.remainingSeconds(), Instant.now(clock).toString());
    }

    /** 手动交卷。 */
    @PostMapping("/submissions")
    public SittingDtos.SubmitResponse submit(@PathVariable UUID offeringId,
                                             @RequestBody SittingDtos.SubmitRequest body,
                                             @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        requireStudent(me);
        requireParticipant(o, me);
        ExamSessionService.Finalized f = sessions.submit(o, me.id(), body.contentHtml(),
                body.contentText(), body.answers());
        return submitResponse(f);
    }

    /**
     * 文件提交（T061 / AC-6.1 / AC-6.5）：multipart {@code file}，≤ 100 MB，pdf/docx/txt/md。
     *
     * <p>文件先存 StorageBackend、再复用 {@link ExamSessionService#submitFile} 的会话/截止/
     * attempts/定稿逻辑；解析失败或无文本层时响应里 {@code unreadable=true}，不进 AI 队列。
     */
    @PostMapping(value = "/submissions/file", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public SittingDtos.SubmitResponse submitFile(@PathVariable UUID offeringId,
                                                 @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                                 @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        requireStudent(me);
        requireParticipant(o, me);
        SubmissionAttachments.Prepared prepared = attachments.prepare(o.getId(), file);
        ExamSessionService.Finalized f = sessions.submitFile(o, me.id(),
                new ExamSessionService.FileAttachment(prepared.storagePath(), prepared.fileName(),
                        prepared.mime(), prepared.text(), prepared.unreadable()));
        return submitResponse(f);
    }

    /** T062：视频上传；ffprobe 校验时长/宽高，超限指出具体项。 */
    @PostMapping(value = "/submissions/video", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public SittingDtos.SubmitResponse submitVideo(@PathVariable UUID offeringId,
                                                  @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                                  @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        requireStudent(me);
        requireParticipant(o, me);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择要提交的视频");
        }
        if (file.getSize() > 100L * 1024 * 1024) {
            throw ApiException.badRequest("视频不能超过 100 MB");
        }
        String name = file.getOriginalFilename() == null ? "video" : file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        if (!java.util.Set.of("mp4", "mov", "webm").contains(ext)) {
            throw ApiException.badRequest("只支持 mp4 / mov / webm 视频");
        }
        java.nio.file.Path temp = null;
        try {
            temp = java.nio.file.Files.createTempFile("teachloop-video-", "." + ext);
            java.nio.file.Files.write(temp, file.getBytes());
            VideoProbe.VideoInfo info = videoProbe.probe(temp);
            var violations = VideoRules.violations(info);
            if (!violations.isEmpty()) {
                throw ApiException.badRequest(String.join("；", violations));
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(temp);
            String mime = file.getContentType() == null ? "video/" + ext : file.getContentType();
            String key = attachments.storeVideo(o.getId(), bytes, ext, mime);
            ExamSessionService.Finalized f = sessions.submitVideo(o, me.id(),
                    new ExamSessionService.FileAttachment(key, name, mime, null, false));
            return submitResponse(f);
        } catch (java.io.IOException ex) {
            throw ApiException.badRequest("视频读取失败，请重试");
        } catch (VideoProbe.ProbeException ex) {
            throw ApiException.badRequest(ex.getMessage());
        } finally {
            if (temp != null) {
                try {
                    java.nio.file.Files.deleteIfExists(temp);
                } catch (java.io.IOException ignored) {
                    // 临时文件清理失败不影响提交结果
                }
            }
        }
    }

    private SittingDtos.SubmitResponse submitResponse(ExamSessionService.Finalized f) {
        return new SittingDtos.SubmitResponse(f.submission().getId().toString(),
                f.submission().getAttemptNo(), f.objectiveScore(), java.math.BigDecimal.valueOf(f.totalPoints()),
                f.autoSubmitted(), f.lateBySeconds(), f.submission().getGradingState().name(),
                f.submission().isUnreadable(), f.submission().getAttachmentName());
    }

    /** 学生的全部尝试：含拒收状态与理由，可据此重交（T202 / AC-8.6）。 */
    @GetMapping("/my-submissions")
    public SittingDtos.MyAttemptsView myAttempts(@PathVariable UUID offeringId,
                                                  @RequestAttribute("currentUser") CurrentUser me) {
        Offering o = published(offeringId);
        requireStudent(me);
        requireParticipant(o, me);
        List<Submission> attempts = submissions.findByOfferingIdAndStudentIdOrderByAttemptNoDesc(
                o.getId(), me.id());
        Submission active = submissions.findByOfferingIdAndStudentIdAndActiveTrue(o.getId(), me.id())
                .orElse(null);
        // 只有「没有进行中的作答」时才可能重新开始：被拒收后 active 会被清掉。
        // 已交卷但未拒收的尝试不算可重交（T064 的 new attempt 不在本阶段范围内）。
        // 已交卷但仍有次数时允许再考一次（T064）；有未交卷草稿时只能继续，不能新开。
        boolean canRetry = attempts.size() < o.getMaxAttempts()
                && (active == null || active.getSubmittedAt() != null);
        List<SittingDtos.AttemptView> views = attempts.stream()
                .sorted(Comparator.comparingInt(Submission::getAttemptNo))
                .map(this::attemptView)
                .toList();
        return new SittingDtos.MyAttemptsView(o.getId().toString(), attempts.size(), o.getMaxAttempts(),
                canRetry, active == null ? null : active.getAttemptNo(), views);
    }

    /** 拒收单份提交：填理由，学生可见；若仍有尝试次数可重交（T202 / AC-8.6）。 */
    @PostMapping("/submissions/{submissionId}/reject")
    @Transactional
    public SittingDtos.AttemptView reject(@PathVariable UUID offeringId, @PathVariable UUID submissionId,
                                          @RequestBody SittingDtos.RejectRequest body,
                                          @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能拒收提交");
        }
        Offering o = published(offeringId);
        Submission s = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("提交不存在"));
        if (!s.getOfferingId().equals(o.getId())) {
            throw ApiException.notFound("提交不存在");
        }
        if (s.getSubmittedAt() == null) {
            throw ApiException.conflict("只有已交卷的提交才能拒收");
        }
        if (s.getRejectedAt() != null) {
            throw ApiException.conflict("这份提交已经拒收");
        }
        String reason = body == null || body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty()) {
            throw ApiException.badRequest("拒收必须填写理由");
        }
        Instant now = Instant.now(clock);
        s.setRejectedAt(now);
        s.setRejectReason(reason);
        s.setActive(false);
        s.setGradingState(GradingState.SKIPPED);
        submissions.save(s);
        audit.record(new AuditEvent(me.id(), "submission:reject", "submission", s.getId().toString(),
                Map.of("offeringId", o.getId().toString(), "lateSeconds", s.getLateBySeconds())));
        return attemptView(s);
    }

    // ---------- internals ----------

    private String notice(Offering o, ExamSessionService.SessionView s, Instant now) {
        if (o.getKind() != OfferingKind.EXAM) {
            return "本次为作业：没有倒计时，过了截止时间仍可提交，会标记为迟交。";
        }
        if (o.getTimingMode() == TimingMode.FIXED_WINDOW) {
            Instant end = s.deadlineAt() != null ? s.deadlineAt()
                    : (o.getStartAt() == null ? null
                    : o.getStartAt().plusSeconds(60L * windows.forOffering(o).durationMinutes()));
            if (o.getStartAt() != null && now.isBefore(o.getStartAt())) {
                return "本场将于 " + HOUR_MINUTE.format(o.getStartAt().atZone(EXAM_ZONE)) + " 统一开考";
            }
            return end == null ? "本场为统一开考"
                    : "已统一开考，本场在 " + HOUR_MINUTE.format(end.atZone(EXAM_ZONE)) + " 结束";
        }
        return "本场为个人计时：点「开始答题」后计时开始。";
    }

    private SittingDtos.SessionView sessionDto(Offering o, UUID studentId, ExamSessionService.SessionView s) {
        return new SittingDtos.SessionView(
                s.submission() == null ? null : s.submission().getId().toString(),
                s.submission() == null ? 0 : s.submission().getAttemptNo(),
                str(s.startedAt()), str(s.deadlineAt()), s.remainingSeconds(), Instant.now(clock).toString(),
                s.started(), s.submitted(), s.autoSubmitted(), s.locked(), o.getTimingMode().name());
    }

    private SittingDtos.AttemptView attemptView(Submission s) {
        return new SittingDtos.AttemptView(s.getAttemptNo(), str(s.getSubmittedAt()), s.isAutoSubmitted(),
                s.getLateBySeconds(), str(s.getRejectedAt()), s.getRejectReason(), s.getGradingState().name(),
                s.getObjectiveTotal(), s.getSubjectiveTotal(),
                s.getObjectiveTotal().add(s.getSubjectiveTotal()), s.isActive(),
                s.getId().toString(), s.getAttachmentName(), s.getContentHtml(), s.getContentText());
    }

    private Offering published(UUID id) {
        Offering o = offerings.findById(id).orElseThrow(() -> ApiException.notFound("布置不存在"));
        if (o.getStatus() != OfferingStatus.PUBLISHED) {
            throw ApiException.notFound("布置不存在");
        }
        return o;
    }

    private void requireParticipant(Offering o, CurrentUser me) {
        if (!windows.isParticipant(o, me.id())) {
            throw ApiException.notFound("考试不存在");
        }
    }

    private static void requireStudent(CurrentUser me) {
        if (!me.is("STUDENT")) {
            throw ApiException.forbidden("只有学生可以作答");
        }
    }

    private static String str(Instant t) {
        return t == null ? null : t.toString();
    }
}
