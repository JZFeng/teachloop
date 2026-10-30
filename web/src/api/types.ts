// 后端 DTO 的前端镜像。字段名与 Java record 的 JSON 序列化一致（camelCase）。
export type Role = 'ADMIN' | 'TEACHER' | 'STUDENT'

export interface Me {
  id: string
  displayName: string
  email: string
  role: Role
  locale: string
}

export interface ApiErrorBody {
  code: string
  message: string
  detail?: string | null
}

export interface ClassView {
  id: string
  name: string
  inviteCode: string
  /** 只数 `ACTIVE` 成员（`LEFT` 不算人；SPEC 012 §8 的统计口径）。 */
  memberCount: number
  /** 结课（归档）：学生仍可见但只读，邀请码失效，不能再布置新作业（AC-98.1 / AC-98.2）。 */
  archived: boolean
}

/** T042：成员列表带姓名与提交数（`GET /api/classes/{id}/members`）。 */
export interface ClassMemberView {
  studentId: string
  displayName: string
  submissionCount: number
  /** 退班 / 移出只写状态，不删成员行（AC-96.2 / AC-97.2 / FR-215）。 */
  status: 'ACTIVE' | 'LEFT'
  /** 离开时间；`ACTIVE` 时为 null。 */
  leftAt: string | null
}

/** `GET /api/classes/{id}/leave-preview`：退出确认先报未交份数（AC-96.5）。 */
export interface ClassLeavePreviewView {
  classId: string
  pendingAssignments: number
  submittedAssignments: number
  totalAssignments: number
  archived: boolean
}

export interface PaperView {
  id: string
  title: string
  totalPoints: number
  version: number
  rubricVersion?: number
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
  defaultDurationMinutes: number | null
  assignmentCount?: number
  /** D-34：命题人 / 审题人是自由文本姓名；旧卷两列为空。 */
  authorName?: string | null
  reviewerName?: string | null
  /** D-34：教师手填的期望总分（发布闸门），不覆盖自动合计。 */
  targetTotalPoints?: number | null
}

export interface PaperItem {
  id: string
  number: number
  questionType: 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'FILL_BLANK' | 'ESSAY'
  stem: string | null
  options: string | null
  answerKey: string | null
  points: number
  scoringStrategy?: 'ALL_OR_NOTHING' | 'PARTIAL'
  /** D-24：写作题自己的评分标准；为空回退试卷级 rubric。 */
  rubricMarkdown?: string | null
  /** D-25：参考范文；只给教师端看，不进 AI prompt。 */
  referenceMarkdown?: string | null
}

export interface PaperSection {
  id: string
  title: string
  position: number
  pointsPerItem: number
  items: PaperItem[]
}

export interface PaperImportItem {
  number: number
  stem: string
  options: string | null
  answerKey: string | null
  points?: number | string | null
  questionType?: string
}

/** 导入时按文档里的「第X部分」落成的部分（单部分文档沿用选中的目标部分）。 */
export interface PaperImportSection {
  title: string
  itemCount: number
  pointsPerItem: number | string | null
}

export interface PaperImportMetaHints {
  authorName?: string | null
  reviewerName?: string | null
  targetTotalPoints?: number | null
}

export interface PaperImportResult {
  imported: number
  missingAnswers: number[]
  items: PaperImportItem[]
  sections?: PaperImportSection[]
  warnings: string[]
  /** D-34：服务端落库的原文（上传 .docx/.xlsx 时编辑器要回填这份）。 */
  contentMarkdown?: string | null
  /** D-34：原文头部解析出的命题人 / 审题人 / 总分，只用于回填空白字段。 */
  metaHints?: PaperImportMetaHints | null
}

export interface PaperDetail {
  paper: PaperView
  sections: PaperSection[]
  criteriaNames: string[]
  /** 试卷级 rubric（考试判分用）；向导打开时要回填，否则编辑器永远是空的。 */
  rubricMarkdown: string | null
  gradingPromptTemplate: string | null
  /** D-28：作文默认内容占比（语法与拼写取其余），只用于界面回显当前系统配置。 */
  essayContentPercent?: number
  /** D-34：最近一次「生成考题」提交的原文，编辑模式用它回填编辑器；旧卷为空。 */
  contentMarkdown?: string | null
}

export type OfferingKind = 'EXAM' | 'ASSIGNMENT'
export type TimingMode = 'INDIVIDUAL' | 'FIXED_WINDOW'
export type OfferingStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED'

export interface OfferingClassView {
  classId: string
  dueAtOverride: string | null
  durationOverride: number | null
  memberCount: number
}

export interface OfferingView {
  id: string
  /** 作业不引用试卷（D-14）：作业的 paperId 为 null，标题回退到作业自身的标题。 */
  paperId: string | null
  paperTitle: string
  paperVersion: number
  kind: OfferingKind
  timingMode: TimingMode
  status: OfferingStatus
  startAt: string | null
  dueAt: string
  durationMinutes: number | null
  effectiveDurationMinutes: number
  graceHours: number
  maxAttempts: number
  feedbackLanguage: string
  showPeerOverview: boolean
  publishScores: boolean
  classCount: number
  /** 已交人数：按学生去重，不算被拒收的份（2026-10-26）。 */
  submissionCount: number
  /** 应交人数：本作业各班成员的并集，同一学生带两个班只算一次（2026-10-26）。 */
  expectedCount: number
  classes: OfferingClassView[]
}

export interface ProgressView {
  total: number
  done: number
  running: number
  pending: number
  failed: number
}

export interface UnsubmittedStudent {
  studentId: string
  studentName: string
}

export interface ScoreBucket {
  label: string
  count: number
}

export interface ItemAccuracy {
  number: number
  answered: number
  correct: number
  correctRate: number | null
}

export interface ClassSummaryView {
  classId: string
  expected: number
  submitted: number
  onTime: number
  late: number
  rejected: number
  onTimeRate: number | null
  averageScore: number | null
  unsubmitted: UnsubmittedStudent[]
  distribution: ScoreBucket[]
  itemAccuracy: ItemAccuracy[]
}

export interface OfferingSubmissionView {
  submissionId: string
  studentId: string
  studentName: string
  attemptNo: number
  submittedAt: string | null
  autoSubmitted: boolean
  lateBySeconds: number
  rejectedAt: string | null
  rejectReason: string | null
  gradingState: string
  /** 决策 025：老师公布成绩（或这一份复核已发布）前，三个分数都是 null。 */
  objectiveScore: number | null
  subjectiveScore: number | null
  totalScore: number | null
  active: boolean
  /** 决策 022 D3：这份提交由哪条 provider 判（人工判分为 null）。 */
  gradedProvider: string | null
}

export interface ExplanationGenerateResult {
  generated: number
  skippedExisting: number
  skippedNoStem: number
  failed: number
  total: number
}

export interface GradingDispatchView {
  offeringId: string
  runId: string
  firstDispatch: boolean
  enqueued: number
}

export interface AnswerInput {
  number: number
  answer: string
}

export interface Summary {
  total: number
  done: number
  running: number
  pending: number
  failed: number
}

export interface EntryView {
  offeringId: string
  paperTitle: string
  itemCount: number
  totalPoints: number
  durationMinutes: number
  timingMode: TimingMode
  startAt: string | null
  dueAt: string
  serverNow: string
  started: boolean
  canStart: boolean
  remainingSeconds: number
  deadlineAt: string | null
  submitted: boolean
  autoSubmitted: boolean
  locked: boolean
  notice: string
  notes: string[]
  /** 教师写在这份布置上的评分标准（Markdown）；没写则为 null。 */
  rubric?: string | null
}

export interface SessionView {
  submissionId: string | null
  attemptNo: number
  startedAt: string | null
  deadlineAt: string | null
  remainingSeconds: number
  serverNow: string
  started: boolean
  submitted: boolean
  autoSubmitted: boolean
  locked: boolean
  timingMode: TimingMode
}

export interface ExamPaperItem {
  number: number
  questionType: PaperItem['questionType']
  points: number
  stem: string | null
  options: string | null
  sectionPosition: number
  sectionTitle: string | null
  /** T321：同一 groupKey 的题共用 material；material 只在组内第一条带上。 */
  groupKey?: string | null
  material?: string | null
  /** 阅读题的局部问题（材料已从题干拆出）；其他题为空。 */
  questionStem?: string | null
  /** 完形/语法填空：该题是材料里的第几个空，0 起。 */
  blankIndex?: number | null
}

export interface ExamPaper {
  offeringId: string
  title: string
  totalPoints: number
  durationMinutes: number
  items: ExamPaperItem[]
}

export interface AnswerSaveResponse {
  submissionId: string | null
  objectiveScore: number | null
  remainingSeconds: number
  serverTime: string
}

export interface SubmitResponse {
  submissionId: string
  attemptNo: number
  /** 决策 025：成绩未公布时为 null——学生看不到分数，只能看到「已交卷」。 */
  objectiveScore: number | null
  totalPoints: number
  autoSubmitted: boolean
  lateBySeconds: number
  gradingState: string
  unreadable?: boolean
  attachmentName?: string | null
}

export interface AttemptView {
  attemptNo: number
  submittedAt: string | null
  autoSubmitted: boolean
  lateBySeconds: number
  rejectedAt: string | null
  rejectReason: string | null
  gradingState: string
  /** 决策 025：老师公布成绩（或这一份复核已发布）前，三个分数都是 null。 */
  objectiveScore: number | null
  subjectiveScore: number | null
  totalScore: number | null
  active: boolean
  submissionId: string | null
  attachmentName: string | null
  /** 学生自己提交的正文：contentHtml 已在后端消毒，可直接回显；没有富文本时为 null。 */
  contentHtml: string | null
  contentText: string | null
}

export interface MyAttemptsView {
  offeringId: string
  attemptsUsed: number
  maxAttempts: number
  canRetry: boolean
  activeAttemptNo: number | null
  attempts: AttemptView[]
}

export interface ReportWrongItem {
  itemId: string
  number: number
  stem: string | null
  questionType: string
  points: number
  answered: number
  wrong: number
  correctRate: number
  topWrongAnswer: string | null
  topWrongCount: number
}

export interface WrongItemsReport {
  items: ReportWrongItem[]
  totalItems: number
  offeringCount: number
}

export interface FeedbackWrongItem {
  number: number
  questionType: string
  points: number
  stem: string | null
  given: string
  correctAnswer: string | null
  explanation: string | null
}

export interface FeedbackReviewNote {
  status: string
  comment: string | null
  strengths: string | null
  suggestions: string | null
  createdAt: string
}

export interface FeedbackOverview {
  expected: number
  submitted: number
  myScore: number | null
  classAverage: number | null
  peersVisible: boolean
}

export interface MyFeedback {
  offeringId: string
  paperTitle: string
  totalPoints: number
  feedbackPublished: boolean
  feedbackMessage: string
  latestAttempt: AttemptView | null
  wrongItems: FeedbackWrongItem[]
  review: FeedbackReviewNote | null
  overview: FeedbackOverview
  /** 本卷客观题满分；0 表示整卷没有客观题，页面不显示「客观题」一行。老后端可能不返回该字段。 */
  objectiveMax?: number
}

export interface ReviewView {
  id: string
  submissionId: string
  status: 'PENDING' | 'APPROVED' | 'EDITED' | 'REJECTED'
  teacherId: string | null
  comment: string | null
  strengths: string | null
  suggestions: string | null
  originalSubjective: number | null
  published: boolean
  publishedAt: string | null
  createdAt: string
}

export interface ReviewAnswer {
  itemId: string
  number: number
  answer: string
  /** 提交时落库的对错判定；作文/主观题为 null。 */
  correct: boolean | null
  /** 本题实得分；作文/主观题为 null。 */
  awardedPoints: number | null
}

export interface ReviewItem {
  number: number
  questionType: string
  stem: string | null
  options: string | null
  answerKey: string | null
  points: number
  scoringStrategy: string | null
  sectionPosition: number
  sectionTitle: string | null
  /** 写作题的题项级评分标准（D-24）。 */
  rubricMarkdown?: string | null
}

export interface ReviewDetail {
  submissionId: string
  studentId: string
  studentName: string
  inputKind: string
  contentText: string | null
  /** 写入时已消毒的富文本；非空时复核页按原格式回显。 */
  contentHtml: string | null
  answers: ReviewAnswer[]
  items: ReviewItem[]
  rubricMarkdown: string | null
  feedbackLanguage: string
  objectivePoints: number | null
  subjectivePoints: number | null
  aiSubjectiveDraft: number | null
  /** T253：AI 逐项意见；AI 未参与时为空数组。 */
  aiCriteria: ReviewCriterion[]
  aiFeedback: string | null
  aiOverall: string | null
  aiStrength: string | null
  aiSuggestion: string | null
  aiFailure: string | null
  aiModel: string | null
  aiPromptVersion: string | null
  aiKeySource: string | null
  paragraphCount: number | null
  paragraphBoundarySensitive: boolean
  review: ReviewView
}

export interface ReviewCriterion {
  name: string
  points: number
  maxPoints: number
  comment: string | null
  /** D-26：这是哪道写作题的评分项；旧数据为 null。 */
  itemNumber?: number | null
}

export interface BulkApproveResult {
  approved: number
  skipped: number
  total: number
}

export interface BulkApproveSkip {
  submissionId: string
  reason: string
}

export interface BulkApprovePreview {
  total: number
  approvable: number
  skipped: number
  minScore: number | null
  maxScore: number | null
  skippedItems: BulkApproveSkip[]
}

export interface AiProviderView {
  id: string
  providerId: string
  displayName: string
  baseUrl: string
  protocol: string
  model: string
  models: string[]
  apiKeySet: boolean
  apiKeyMasked: string | null
  active: boolean
  updatedAt: string
  /** 决策 022：故障转移顺序，数值小者优先。 */
  priority: number
  /** 决策 024：provider 级额外请求头（JSON 文本）。 */
  extraHeaders: string | null
}

/** 决策 022：当前是否处于故障转移（不含密钥）。 */
export interface AiProviderStatus {
  active: boolean
  fromProviderKey: string | null
  reason: string | null
  since: string | null
  activeProviderId: string | null
  activeProviderKey: string | null
}

export interface AiProviderRequest {
  providerId: string
  displayName: string
  baseUrl: string
  protocol: string
  apiKey: string | null
  model: string
  models: string[]
  priority: number
  extraHeaders: string | null
}

export interface AiModelOption {
  key: string
  providerId: string
  displayName: string
  model: string
  label: string
}

export interface AiModelsView {
  options: AiModelOption[]
  currentProviderId: string | null
  currentModel: string | null
  currentKey: string | null
}

export interface AiTestResult {
  ok: boolean
  latencyMs: number
  message: string
  model: string
}

/** 教师级作业批改时机（决策 010）。 */
export interface GradingPreference {
  triggerMode: 'FIXED_TIME' | 'DUE_DATE'
  fixedTime: string
  timeZone: string
  catchUp: boolean
  lastFixedRunAt: string | null
  updatedAt: string | null
}

export interface AuditView {
  id: string
  action: string
  actorId: string | null
  createdAt: string
  detail: string | null
}

export interface NotificationView {
  id: string
  kind: string
  targetType: string | null
  targetId: string | null
  title: string
  body: string | null
  readAt: string | null
  createdAt: string
}

export interface AiCircuitView {
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
  providerKey: string | null
  providerDisplayName: string | null
  consecutiveFailures: number
  openedAt: string | null
  nextProbeAt: string | null
  lastErrorKind: string | null
}

/**
 * GET /api/ai-readiness 的返回。
 * `engine === 'stub'` 是仅 QA/测试环境存在的开发替身：不需要 Key 也能出分，
 * 所以「能不能出分」看 `ready`（configured || stub），不是 configuration 本身。
 */
export interface AiReadinessView {
  configured: boolean
  engine: 'ai' | 'stub'
  ready: boolean
  source: 'provider' | 'env' | 'stub' | null
  provider: string | null
  model: string | null
}

/* ——— 班级生命周期的视图层纯函数（SPEC 012 US-96/97/98） ———
 * 为什么放在 DTO 旁边而不是页面文件里：`ClassesPage.tsx` 是 JSX，`node --test` 只做类型擦除、
 * 不转 JSX，这两条规则留在页面里就没有单测；`types.ts` 是本次任务里唯一可动的 `.ts` 文件，
 * 于是「进行中 / 已结束分组」与「按班级筛作业」放在 DTO 旁边，页面与测试共用同一份实现。 */

/** AC-98.2 / FR-224：班级页按「进行中（archived=false）/ 已结束（archived=true）」分组，组内保持后端顺序。 */
export function splitClassesByLifecycle(classes: ClassView[]): {
  active: ClassView[]
  archived: ClassView[]
} {
  const active: ClassView[] = []
  const archived: ClassView[] = []
  for (const item of classes) {
    if (item.archived) archived.push(item)
    else active.push(item)
  }
  return { active, archived }
}

/**
 * 学生班级页展开后看到的作业列表：只留属于这个班的，按截止时间升序
 * （最急的在最上，与 AC-72.7 同口径）。解析不出的截止时间沉到最后，不占「最急」的位置。
 * 入参数组不改动。
 */
export function offeringsForClass(offerings: OfferingView[], classId: string): OfferingView[] {
  const mine = offerings.filter((offering) =>
    (offering.classes ?? []).some((row) => row.classId === classId),
  )
  return mine.sort((a, b) => dueKey(a.dueAt) - dueKey(b.dueAt))
}

/** AC-98.7 / FR-225：已结课的班不能再当「布置作业 / 布置试卷」的目标（服务端回 409 `CLASS_ARCHIVED`）。 */
export function isAssignableClass(row: ClassView): boolean {
  return !row.archived
}

/**
 * 可选目标列表：已结课的班被剔出去，但页面**不把它们从名单里删掉**——
 * 老师要看得见「这个班已结束，所以选不了」，而不是以为班不见了。渲染时禁用 + 写明原因。
 */
export function assignableClasses(classes: ClassView[]): ClassView[] {
  return classes.filter(isAssignableClass)
}

function dueKey(dueAt: string | null | undefined): number {
  if (!dueAt) return Number.POSITIVE_INFINITY
  const time = new Date(dueAt).getTime()
  return Number.isNaN(time) ? Number.POSITIVE_INFINITY : time
}
