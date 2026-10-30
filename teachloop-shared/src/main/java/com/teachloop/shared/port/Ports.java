package com.teachloop.shared.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 跨模块端口的数据契约：只有 record，没有实体，避免懒加载跨模块泄漏。 */
public final class Ports {

    private Ports() {
    }

    /**
     * 一道客观题或一道主观题的评分契约。
     *
     * <p><code>options</code> / <code>sectionPosition</code> / <code>sectionTitle</code> 是 T198 快照里
     * 供学生端渲染试卷用的结构字段；判分逻辑只关心 answerKey / points / questionType。
     */
    public record ItemSpec(UUID itemId, int number, String questionType, String answerKey,
                           BigDecimal points, String stem, String options,
                           int sectionPosition, String sectionTitle, String scoringStrategy,
                           String rubricMarkdown) {

        /** 兼容既有测试与调用方：结构字段缺省。 */
        public ItemSpec(UUID itemId, int number, String questionType, String answerKey,
                        BigDecimal points, String stem) {
            this(itemId, number, questionType, answerKey, points, stem, null, 0, null, "ALL_OR_NOTHING",
                    null);
        }

        /** 兼容 T362 之前调用方：没有题项级 rubric。 */
        public ItemSpec(UUID itemId, int number, String questionType, String answerKey,
                        BigDecimal points, String stem, String options,
                        int sectionPosition, String sectionTitle, String scoringStrategy) {
            this(itemId, number, questionType, answerKey, points, stem, options,
                    sectionPosition, sectionTitle, scoringStrategy, null);
        }

        public ItemSpec withAnswerKey(String key) {
            return new ItemSpec(itemId, number, questionType, key, points, stem, options,
                    sectionPosition, sectionTitle, scoringStrategy, rubricMarkdown);
        }

        public ItemSpec(UUID itemId, int number, String questionType, String answerKey,
                        BigDecimal points, String stem, String options,
                        int sectionPosition, String sectionTitle) {
            this(itemId, number, questionType, answerKey, points, stem, options,
                    sectionPosition, sectionTitle, "ALL_OR_NOTHING", null);
        }
    }

    /**
     * 学生的某一道题的作答。
     *
     * <p>{@code correct} 是提交时落库的判定结果（{@code objective_answers.correct}），
     * {@code awarded} 是按快照标准答案算出的本题实得分；两者只对可自动判分的题有值，
     * 作文/主观题一律为 null。复核页据此把答对/答错/部分对分色显示。
     */
    public record AnswerRow(UUID itemId, int number, String answer,
                            Boolean correct, BigDecimal awarded) {

        /** 兼容既有调用：只关心作答内容时，对错与得分留空。 */
        public AnswerRow(UUID itemId, int number, String answer) {
            this(itemId, number, answer, null, null);
        }
    }

    /** 试卷只读快照（布置时固化，不回查活表）；paperVersion 随快照一起落库，便于追溯。 */
    public record PaperSnapshot(UUID paperId, int paperVersion, String title, int totalPoints,
                                int durationMinutes, List<ItemSpec> items, String rubricMarkdown) {

        public PaperSnapshot withItems(List<ItemSpec> newItems) {
            return new PaperSnapshot(paperId, paperVersion, title, totalPoints, durationMinutes,
                    newItems, rubricMarkdown);
        }
    }

    /**
     * 阅卷需要的一切，一次取齐（胖消息、瘦 Worker）。
     *
     * <p>contentHtml 是写入时已消毒的富文本原文（T250）：AI prompt 只用 contentText，
     * 教师复核页要按原格式回显，所以两个都带。
     */
    public record WorkItem(UUID submissionId, UUID offeringId, UUID studentId, String studentName,
                           String inputKind, String contentText, String contentHtml,
                           List<AnswerRow> answers, List<ItemSpec> items, String rubricMarkdown,
                           String gradingPrompt, String feedbackLanguage, UUID createdBy, String anchors,
                           String offeringTitle) {

        /** 兼容既有测试与调用方：没有作业创建者/锚点/作业标题时按全局 Key、无锚点、无标题处理。 */
        public WorkItem(UUID submissionId, UUID offeringId, UUID studentId, String studentName,
                        String inputKind, String contentText, String contentHtml,
                        List<AnswerRow> answers, List<ItemSpec> items, String rubricMarkdown,
                        String gradingPrompt, String feedbackLanguage) {
            this(submissionId, offeringId, studentId, studentName, inputKind, contentText, contentHtml,
                    answers, items, rubricMarkdown, gradingPrompt, feedbackLanguage, null, null, null);
        }

        public WorkItem(UUID submissionId, UUID offeringId, UUID studentId, String studentName,
                        String inputKind, String contentText, String contentHtml,
                        List<AnswerRow> answers, List<ItemSpec> items, String rubricMarkdown,
                        String gradingPrompt, String feedbackLanguage, UUID createdBy) {
            this(submissionId, offeringId, studentId, studentName, inputKind, contentText, contentHtml,
                    answers, items, rubricMarkdown, gradingPrompt, feedbackLanguage, createdBy, null, null);
        }
    }

    /**
     * 一份提交当前的分数与交卷状态（T243）。
     *
     * <p>老师可以完全不依赖 AI 判分：复核工作台需要读到「当前客观分 / 主观分」才能把人工分
     * 加在正确的基础上，而 {@link WorkItem} 刻意不带分数（阅卷只读作答，不做状态推进）。
     * 本记录只读，不包含学生身份以外的隐私字段。
     *
     * @param submittedAt 交卷时间；null 表示仍在作答（草稿），不允许判分
     * @param gradingState submissions.grading_state 的枚举名（QUEUED / PENDING / RUNNING / DONE / FAILED）
     */
    public record SubmissionScores(UUID submissionId, Instant submittedAt, BigDecimal objectiveTotal,
                                   BigDecimal subjectiveTotal, String gradingState) {
    }

    public record CriterionScore(String name, BigDecimal points, BigDecimal maxPoints, String comment,
                                 Integer itemNumber) {

        /** 兼容既有调用：不区分题号（单篇判分/旧行为）。 */
        public CriterionScore(String name, BigDecimal points, BigDecimal maxPoints, String comment) {
            this(name, points, maxPoints, comment, null);
        }

        public CriterionScore withItemNumber(Integer number) {
            return new CriterionScore(name, points, maxPoints, comment, number);
        }
    }

    public record ItemExplanation(int number, String text) {
    }

    /**
     * @param objectivePoints 客观题得分；null 表示“不改动”，因为客观分在提交时已由代码判定，不允许模型覆盖
     */
    public record GradingResult(BigDecimal objectivePoints, BigDecimal subjectivePoints,
                                List<CriterionScore> criteria, String feedback,
                                List<ItemExplanation> explanations,
                                String overall, String strength, String suggestion,
                                String model, String promptVersion, String keySource) {

        /** 兼容旧调用：只带合并后的 feedback。 */
        public GradingResult(BigDecimal objectivePoints, BigDecimal subjectivePoints,
                             List<CriterionScore> criteria, String feedback,
                             List<ItemExplanation> explanations) {
            this(objectivePoints, subjectivePoints, criteria, feedback, explanations,
                    null, null, null, null, null, null);
        }

        /** 兼容 v2 校验结果：不带模型/Key 溯源。 */
        public GradingResult(BigDecimal objectivePoints, BigDecimal subjectivePoints,
                             List<CriterionScore> criteria, String feedback,
                             List<ItemExplanation> explanations,
                             String overall, String strength, String suggestion) {
            this(objectivePoints, subjectivePoints, criteria, feedback, explanations,
                    overall, strength, suggestion, null, null, null);
        }
    }

    /** sitting 暴露的**查询**端口：阅卷只读作答，不做任何状态推进。 */
    public interface SittingLedger {
        WorkItem load(UUID submissionId);

        /**
         * T243：当前分数与交卷时间；提交不存在时抛 notFound。
         *
         * <p>只读，不改任何状态；人工判分的「当前实际分」以这里为准。
         */
        SubmissionScores scores(UUID submissionId);

        /** D-31：从 since 起失败的提交所涉及的作业创建者（熔断通知与重派范围用）。 */
        List<UUID> teacherIdsWithFailedGradingSince(Instant since);

        /**
         * 反馈通知的「哪个班、哪位老师」；查不到时返回字段为 null 的记录，不抛异常。
         *
         * <p>2026-10-25：学生要求反馈通知也写清班级与老师。班级归属在 sitting/classroom 的表里，
         * grading 只能走端口问；查不到就回退泛化文案。
         */
        OfferingAudience audienceOf(UUID offeringId, UUID studentId);
    }

    /**
     * 反馈通知展示用：这场布置里这个学生所在的班名与布置老师的显示名。
     *
     * @param className 学生在这批参与班里的第一个班；没有交集时为 null
     * @param teacherName 布置老师的显示名原文（末尾自带的「老师」由通知读取侧去重）；查不到为 null
     */
    public record OfferingAudience(String className, String teacherName) {
    }

    /**
     * sitting 暴露的**命令**端口：领取与推进工作项。
     * 将来换成消息队列仍要求至少一次投递，所以每个方法都必须幂等。
     */
    public interface GradingWorkQueue {
        /** 待批改的工作项（PENDING），返回上限 limit 条。 */
        List<UUID> pendingIds(int limit);

        void markRunning(UUID submissionId);

        void markDone(UUID submissionId, GradingResult result);

        /** 决策 022 D3：记录这份提交由哪条 provider 判（同一场可能跨 provider）。 */
        void markProvider(UUID submissionId, String providerKey);

        void markFailed(UUID submissionId, String error);

        /**
         * 这场布置还有没有没跑完的提交（PENDING / RUNNING，以及还没派发的 QUEUED）。
         * T335：worker 用它判断「这一批跑完了没有」，跑完了就把 grading_runs 收尾。
         */
        boolean hasOpenWork(UUID offeringId);

        /** D-31：熔断恢复后把窗口内的 FAILED 退回 PENDING；每份上限 1 次。返回重派份数。 */
        int requeueCircuitFailed(Instant since, Instant until);
    }

    /**
     * grading 暴露的命令端口：到期派发一场考试，一个 offering 只产生一条 run。
     * 重复调用返回同一条 run 的 id（幂等）。
     */
    public interface GradingDispatch {
        UUID dispatch(UUID offeringId);
    }

    /** grading 暴露：sitting 回写分数。 */
    public interface GradingOutcome {
        void apply(UUID submissionId, GradingResult result);
    }

    /** paper 暴露。 */
    public interface PaperCatalog {
        PaperSnapshot load(UUID paperId);
    }

    /** classroom 暴露：班级成员关系（只读，不暴露学生资料）。 */
    public interface ClassMembership {
        List<UUID> classIdsOf(UUID studentId);

        List<UUID> membersOf(UUID classId);

        /** T329：这批班级里哪些属于该教师（报表按班级取数时的归属校验）。 */
        java.util.Set<UUID> classIdsOwnedBy(java.util.Collection<UUID> classIds, UUID teacherId);

        /**
         * 班级名（只给展示用，例如通知文案里的「三年二班」）；查不到的班级不出现在返回里。
         *
         * <p>2026-10-24 学生反馈：发词通知要写出「哪个班的哪位老师」，跨模块只能走端口取名字。
         */
        java.util.Map<UUID, String> classNames(java.util.Collection<UUID> classIds);
    }

    /** 管理员/教师保存的 AI 配置；key 不随端口返回给业务方以外的接口。 */
    public record AiSettings(String provider, String baseUrl, String model, String apiKey,
                             BigDecimal temperature, boolean configured, String source,
                             java.util.Map<String, String> extraHeaders) {

        public AiSettings {
            extraHeaders = extraHeaders == null ? java.util.Map.of() : java.util.Map.copyOf(extraHeaders);
        }

        public AiSettings(String provider, String baseUrl, String model, String apiKey,
                          BigDecimal temperature, boolean configured, String source) {
            this(provider, baseUrl, model, apiKey, temperature, configured, source, java.util.Map.of());
        }

        public AiSettings(String provider, String baseUrl, String model, String apiKey,
                          BigDecimal temperature, boolean configured) {
            this(provider, baseUrl, model, apiKey, temperature, configured, configured ? "global" : "none",
                    java.util.Map.of());
        }
    }

    /**
     * AI 就绪状态（AI 未配置时手动派发必须拦在入队之前）。
     *
     * @param configured 有可用模型：当前已选 provider，或部署级 DEEPSEEK_API_KEY 非空
     * @param engine     批改引擎：ai / stub（stub 是开发替身，不需要 Key 也能出分）
     * @param ready      现在点「让 AI 批」能不能出分：configured || engine == stub
     * @param source     模型来源：provider / env / stub / null（都没有）
     * @param provider   providerKey，只做展示；绝不带 Key
     * @param model      当前模型名，只做展示
     */
    public record AiReadiness(boolean configured, String engine, boolean ready, String source,
                              String provider, String model) {
    }

    public interface AiSettingsLookup {
        java.util.Optional<AiSettings> current();

        /** D-16：本人 Key 优先，缺失回退全局；返回的 source 是 user / global。 */
        java.util.Optional<AiSettings> resolve(UUID teacherId);

        /**
         * AI 就绪状态。默认实现只看全局配置，不看环境变量与引擎开关：
         * 没有 identity 装配（单元测试、其它实现）时行为保持不变，调用方据此放行。
         * identity 的 {@code AiSettingsLookupImpl} 会覆写为完整的部署级判断。
         */
        default AiReadiness readiness() {
            java.util.Optional<AiSettings> settings = current().filter(AiSettings::configured);
            if (settings.isEmpty()) {
                return new AiReadiness(false, "ai", false, null, null, null);
            }
            AiSettings s = settings.get();
            return new AiReadiness(true, "ai", true, s.source(), s.provider(), s.model());
        }
    }

    /** T102：改标准答案后重算客观分。 */
    public record AnswerKey(UUID paperId, int number, String answerKey) {
    }

    public interface ObjectiveRecompute {
        int recomputePaper(UUID paperId, List<AnswerKey> keys);
    }

    /** 试卷被布置次数（T206）：sitting 只读聚合。 */
    public interface PaperUsage {
        int assignmentCount(UUID paperId);
    }

    /** 班级成员提交数（T042）：sitting 只读聚合，classroom 负责展示。 */
    public record ClassSubmissionCount(UUID studentId, int count) {
    }

    public interface ClassSubmissionStats {
        List<ClassSubmissionCount> submissionCounts(UUID classId);
    }

    /**
     * 归属查询（T329）：阅卷模块不读 sitting 的表，只能问「这些提交归不归这位老师」。
     *
     * <p>没有这个端口，复核队列、复核详情与批量通过就只能是「同角色即可看/可改」，
     * 教师首页「待复核」会混进别的老师的提交，知道 id 就能改别人的分数。
     */
    public interface OfferingOwnership {
        /** 在一批提交里挑出属于该教师自己布置的（一次查询）。 */
        java.util.Set<java.util.UUID> ownedSubmissionIds(java.util.Collection<java.util.UUID> submissionIds,
                                                         java.util.UUID teacherId);

        /** 在一批布置里挑出该教师自己布置的（报表按 id 列表取数时用）。 */
        java.util.Set<java.util.UUID> ownedOfferingIds(java.util.Collection<java.util.UUID> offeringIds,
                                                       java.util.UUID teacherId);

        /**
         * 在一批提交里挑出仍然 active 的（学生重交后，旧 attempt 变 inactive）。
         * 复核队列用它去掉被取代的旧草稿：同一场同一个学生只该复核最新一版。
         */
        java.util.Set<java.util.UUID> activeSubmissionIds(java.util.Collection<java.util.UUID> submissionIds);
    }

    /** 复核结论的只读投影：学生反馈用，不暴露教师身份。 */
    public record ReviewNote(String status, String comment, String strengths, String suggestions,
                             String createdAt, String publishedAt) {
    }

    /** 生成解析所需的一道错题（题目级，不含学生身份）。 */
    public record ExplanationItem(java.util.UUID itemId, int number, String questionType, String stem,
                                  String options, String answerKey, int wrongCount, String language) {
    }

    /** sitting 暴露：某场考试需要生成解析的错题（按答错人次降序）。 */
    public interface ExplanationWorkQueue {
        java.util.List<ExplanationItem> wrongItemsOf(java.util.UUID offeringId, int limit);
    }

    /** grading 暴露：错题解析（题目级共享，按语言与 prompt 版本缓存）。 */
    public interface ExplanationCatalog {
        java.util.Optional<String> explanationOf(UUID itemId, String language);
    }

    /** grading 暴露：学生反馈读复核结论（只读）。 */
    public interface ReviewFeedback {
        java.util.Optional<ReviewNote> noteOf(UUID submissionId);
    }

    /** identity 暴露：只读显示名/界面语言，用于教师端提交表与反馈语言；不暴露联系方式。 */
    public interface StudentDirectory {
        java.util.Map<UUID, String> displayNames(java.util.Collection<UUID> userIds);

        /** 学生的界面语言（`zh-CN` / `en`）；缺失时由调用方回退。 */
        java.util.Map<UUID, String> locales(java.util.Collection<UUID> userIds);
    }

    /**
     * 一条站内通知。
     *
     * <p>A1（2026-10-24）：文案不再由调用方按语言拼好再落库，而是传 {@code payload}（事实），
     * identity 在<b>读取时</b>按请求的 {@code Accept-Language} 渲染。这样学生切语言后历史通知也跟着变。
     *
     * @param payload 模板参数（如 {@code title} / {@code score}）；{@code null} 表示历史行或未知 kind，
     *                直接回退显示 {@code title}/{@code body} 里存的原文
     */
    public record NotificationMessage(UUID recipientId, String kind, String targetType, String targetId,
                                      String title, String body, java.util.Map<String, String> payload) {

        /** 兼容既有调用方与测试：只给成品文案，读的时候原样显示（不回退模板）。 */
        public NotificationMessage(UUID recipientId, String kind, String targetType, String targetId,
                                   String title, String body) {
            this(recipientId, kind, targetType, targetId, title, body, null);
        }
    }

    /** identity 暴露；接收方与联系方式不外出。 */
    public interface NotificationPort {
        void notifyOfferingPublished(UUID offeringId);

        void notifyScoresPublished(UUID offeringId);

        /** D-31：给全部管理员发一条站内通知；接收方由 identity 解析，调用方不需要知道管理员是谁。 */
        void notifyAdmins(NotificationMessage message);

        /** 批量写入站内通知；重复调用不保证去重（由调用方保证语义）。 */
        default void notify(java.util.List<NotificationMessage> messages) {
        }
    }

    /**
     * D-31：AI 调用熔断。状态按 provider_key 存库，app 与 worker 两个进程共享。
     *
     * <p>{@link #acquire} 返回本轮允许领取的份数：0 = 熔断中；1 = 正常或半开试探（只放 1 份）。
     */
    /** D-31：熔断打开事件；{@code opened=true} 表示本次触发了 OPEN（含半开失败重新 OPEN）。 */
    public record CircuitOpenEvent(boolean opened, Instant openedAt, Instant streakStartedAt) {
    }

    /** D-31：熔断状态快照（状态接口与测试用）。 */
    public record CircuitSnapshot(String providerKey, String state, int consecutiveFailures,
                                  Instant openedAt, Instant nextProbeAt, String lastErrorKind,
                                  Instant streakStartedAt) {
    }

    public interface AiCircuit {
        int acquire(String providerKey, Instant now);

        /** 一份成功；返回 true 表示本次把 HALF_OPEN 关回了 CLOSED。 */
        boolean reportSuccess(String providerKey, Instant now);

        /** 一份失败；返回的事件里 opened 表示本次是否触发了 OPEN。 */
        CircuitOpenEvent reportFailure(String providerKey, String errorKind, Instant now);

        CircuitSnapshot snapshot(String providerKey);
    }

    /**
     * 决策 022：provider 故障转移与自动回切。
     *
     * <p>由熔断状态机在「打开」与「一份成功」时调用；实现方负责改 active provider、
     * 写审计与通知。开关判定放在实现方，熔断机只负责告知事实。
     */
    public interface AiProviderFailover {

        /** 某条 provider 的熔断刚被打开。 */
        void onCircuitOpen(String failedProviderKey, Instant now);

        /** 一份判分成功（用于累计成功连续数并判断能否回切主 provider）。 */
        void onGradingSuccess(String providerKey, Instant now);
    }

    // ────────────────────────────────────────────────────────────────
    // 011-self-study-mode：今日页聚合所需的三段契约
    // ────────────────────────────────────────────────────────────────

    /** 复习切片：待复习 / 今日已复习 / 今日新词 / 每日新卡上限。 */
    public record ReviewSlice(int due, int reviewedToday, int newToday, int newLimit) {
    }

    /** 待交作业的一行：班级名由调用方用 ClassMembership 批量补，不跨模块 join。 */
    public record AssignmentRow(UUID id, String title, String className,
                                Instant dueAt, String status) {
    }

    /** 未来考试的一行。 */
    public record ExamRow(UUID id, String title, String className, Instant startsAt) {
    }

    /** 学生的时区（IANA 名）。空 Optional = 未设置，由调用方回落产品默认。 */
    public record UserZone(String iana) {
    }

    /** 由 teachloop-vocab 实现：按学生时区算「今天」的复习切片。 */
    public interface ReviewSlicePort {

        ReviewSlice today(UUID userId, String iana);
    }

    /** 由 teachloop-sitting 实现：学生的待交作业与未来考试。 */
    public interface AgendaPort {

        List<AssignmentRow> pending(UUID studentId);

        List<ExamRow> upcoming(UUID studentId, Instant from, Instant to);
    }

    /** 由 teachloop-identity 实现：读 users.timezone（只读单行）。 */
    public interface UserZonePort {

        Optional<UserZone> of(UUID userId);
    }

    // ────────────────────────────────────────────────────────────────
    // 013-word-books：系统词书推荐
    // ────────────────────────────────────────────────────────────────

    /** 推荐位展示所需的最小字段（覆盖率实时算，不落库）。 */
    public record BookRef(UUID id, String name, int wordCount, int readyCount) {
    }

    /**
     * 词书推荐规则。v1 按「已发布 → position → 加入人数」；以后按水平/班级推荐时只换实现，
     * 控制器与前端不动（013 FR-250）。
     */
    public interface BookRecommendation {

        List<BookRef> recommended(UUID userId, int limit);
    }

    // ────────────────────────────────────────────────────────────────
    // 012-class-lifecycle：班级状态只读查询
    // ────────────────────────────────────────────────────────────────

    /** 一个班的生命周期状态：是否结课、当前用户是否仍在班。 */
    public record ClassState(UUID classId, boolean archived, boolean active) {
    }

    /** 由 teachloop-classroom 实现：sitting / app 判断能否提交、能否计入今日页。 */
    public interface ClassLifecycle {

        Optional<ClassState> state(UUID classId);
    }
}
