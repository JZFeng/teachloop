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

export interface PaperView {
  id: string
  title: string
  totalPoints: number
  version: number
  status: 'DRAFT' | 'PUBLISHED'
  defaultDurationMinutes: number | null
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
}

export interface PaperImportResult {
  imported: number
  missingAnswers: number[]
  items: PaperImportItem[]
  warnings: string[]
}

export interface PaperDetail {
  paper: PaperView
  sections: PaperSection[]
  criteriaNames: string[]
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
  paperId: string
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
}

export interface ReviewDetail {
  submissionId: string
  studentId: string
  studentName: string
  inputKind: string
  contentText: string | null
  answers: ReviewAnswer[]
  items: ReviewItem[]
  rubricMarkdown: string | null
  feedbackLanguage: string
  objectivePoints: number | null
  subjectivePoints: number | null
  aiSubjectiveDraft: number | null
  review: ReviewView
}

export interface BulkApproveResult {
  approved: number
  skipped: number
  total: number
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
