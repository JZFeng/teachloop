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
  memberCount: number
}

/** T042：成员列表带姓名与提交数（`GET /api/classes/{id}/members`）。 */
export interface ClassMemberView {
  studentId: string
  displayName: string
  submissionCount: number
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

export interface PaperImportResult {
  imported: number
  missingAnswers: number[]
  items: PaperImportItem[]
  sections?: PaperImportSection[]
  warnings: string[]
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
  submissionCount: number
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
  objectiveScore: number
  subjectiveScore: number
  totalScore: number
  active: boolean
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
  objectiveScore: number
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
  objectiveScore: number
  subjectiveScore: number
  totalScore: number
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
  /** 本卷客观题满分；0 表示整卷没有客观题，页面不显示「客观题」一行。 */
  objectiveMax: number
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
}

export interface AiProviderRequest {
  providerId: string
  displayName: string
  baseUrl: string
  protocol: string
  apiKey: string | null
  model: string
  models: string[]
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
