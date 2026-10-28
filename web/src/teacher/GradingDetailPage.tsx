import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { isAIReady } from '../api/ai'
import { useAuth } from '../api/auth'
import { api, fmtDateTime } from '../api/client'
import type {
  ClassSummaryView,
  ClassView,
  ExplanationGenerateResult,
  GradingDispatchView,
  OfferingSubmissionView,
  OfferingView,
  ProgressView,
} from '../api/types'
import { AuditDrawer } from '../components/AuditDrawer'
import { AiCircuitBanner } from '../components/AiCircuitBanner'
import { useListState } from '../ui'
import { useAiReadiness } from '../components/useAiReadiness'
import {
  Alert,
  Button,
  ColumnPicker,
  DataTable,
  ErrorState,
  Field,
  Input,
  Loading,
  PageHeader,
  RowActions,
  Search,
  StatCard,
  TableToolbar,
  ViewTabs,
  useColumnPrefs,
} from '../ui'
import type { Column } from '../ui'
import { defaultTab, detailGates } from './detailGates'
import {
  errorMessage,
  fmtLate,
  fmtNum,
  SubmissionStateTag,
  Tag,
  TeacherPage,
  useApiData,
} from './common'

interface DetailData {
  offering: OfferingView
  progress: ProgressView
  submissions: OfferingSubmissionView[]
  classes: ClassView[]
  /** 未交名单从这个接口取（与班级汇总页同一个数据源，不新增接口）。 */
  summary: ClassSummaryView[]
}

/** 逐份提交的筛选档位；`all` 不在这里——「不选任何一档」就是全部。 */
type FilterKey = 'done' | 'running' | 'pending' | 'failed' | 'rejected'
const FILTERS: FilterKey[] = ['done', 'running', 'pending', 'failed', 'rejected']

/** 列顺序与显隐（列配置存本地，与首页同一套 `useColumnPrefs`）。 */
const COLUMN_ORDER = ['student', 'attempt', 'submittedAt', 'late', 'state', 'objective', 'subjective', 'total', 'actions']
const COLUMN_LABEL_KEY: Record<string, string> = {
  student: 'teacher.detail.colStudent',
  attempt: 'teacher.detail.colAttempt',
  submittedAt: 'teacher.detail.colSubmittedAt',
  late: 'teacher.detail.colLate',
  state: 'teacher.detail.colState',
  objective: 'teacher.detail.colObjective',
  subjective: 'teacher.detail.colSubjective',
  total: 'teacher.detail.colTotal',
  actions: 'teacher.common.actions',
}
const PREFS_KEY = 'teachloop.grading.detail.columns.v1'

/**
 * T182 / AC-20.5–AC-20.7：逐份提交表。
 * 派发回执直接显示在页面；拒收走行内表单（不使用 prompt/confirm）；
 * 「重试失败项」只把 FAILED 退回队列，运行中与已完成的不动。
 *
 * 2026-10-26（用户拍板）：这页重做四件事——
 *   ① 视图切换做成真页签（原来那对 Segmented 用户根本没看到），默认页签随「成绩是否公布」；
 *   ② 筛选条删掉，换成与首页/词库同一套工具栏（筛选 · N / 列 / 清空）；
 *   ③ 动作可用性随状态收敛：没人交卷时派发/解析/复核/公布全禁用并写明原因；
 *   ④ 没人交卷时不再摆 4 张 0 卡，直接给「谁没交」的名单（数据来自 class-summary）。
 */
export default function GradingDetailPage() {
  const { t } = useTranslation('translation')
  const { offeringId = '' } = useParams()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(
    { key: 'submittedAt', dir: 'desc' },
    10,
  )
  const aiReadiness = useAiReadiness()
  const { me } = useAuth()
  // 模型只在 /admin/ai 配（2026-10-24 决策）：管理员给一个跳转，教师只看到「请联系管理员」。
  const aiSetupTarget = me?.role === 'ADMIN' ? '/admin/ai' : null

  const { data, loading, error, reload } = useApiData<DetailData>(async () => {
    const [offering, progress, submissions, classes, summary] = await Promise.all([
      api.get<OfferingView>(`/api/offerings/${offeringId}`),
      api.get<ProgressView>(`/api/offerings/${offeringId}/progress`),
      api.get<OfferingSubmissionView[]>(`/api/offerings/${offeringId}/submissions`),
      api.get<ClassView[]>('/api/classes'),
      api.get<ClassSummaryView[]>(`/api/offerings/${offeringId}/class-summary`),
    ])
    return { offering, progress, submissions, classes, summary }
  }, [offeringId])

  const [dispatchReceipt, setDispatchReceipt] = useState<GradingDispatchView | null>(null)
  const [dispatchError, setDispatchError] = useState<string | null>(null)
  const [dispatching, setDispatching] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const [retryNote, setRetryNote] = useState<{ retried: number; reclaimed: number } | null>(null)
  const [aiDispatchingId, setAiDispatchingId] = useState<string | null>(null)
  const [aiDispatchNote, setAiDispatchNote] = useState<string | null>(null)
  const [aiDispatchError, setAiDispatchError] = useState<string | null>(null)
  // AI 没配好时点了任何一个 AI 动作：不发请求，只把这条提示亮出来。
  const [aiBlocked, setAiBlocked] = useState(false)
  const [explaining, setExplaining] = useState(false)
  const [explainResult, setExplainResult] = useState<ExplanationGenerateResult | null>(null)
  const [explainError, setExplainError] = useState<string | null>(null)
  const [rejectingId, setRejectingId] = useState<string | null>(null)
  const [rejectReason, setRejectReason] = useState('')
  const [rejectError, setRejectError] = useState<string | null>(null)
  const [rejecting, setRejecting] = useState(false)
  // 决策 025：成绩以老师公布为准，所以这一页必须有公布入口，否则学生永远看不到分。
  const [publishingScores, setPublishingScores] = useState(false)
  const [publishScoresNote, setPublishScoresNote] = useState<string | null>(null)
  const [publishScoresError, setPublishScoresError] = useState<string | null>(null)
  const [auditTarget, setAuditTarget] = useState<OfferingSubmissionView | null>(null)

  const [columnsOpen, setColumnsOpen] = useState(false)
  const colPrefs = useColumnPrefs(PREFS_KEY, COLUMN_ORDER)

  /** 多选筛选：`?state=done&state=failed`；一组都不选＝全部。 */
  const activeStates = params
    .getAll('state')
    .filter((value): value is FilterKey => (FILTERS as string[]).includes(value))

  /**
   * 2026-10-26（用户要求）：提交明细也照首页 / 词库那套摆一个常驻搜索框。
   * 平台没有单独的「学号」列（`OfferingExportService.java:101` 记着这件事：重名时用学生 id 前 8 位做后缀），
   * 所以搜索匹配**学生姓名**或**学生 ID**（表格姓名下面那行 8 位就是 ID 前缀，可以直接粘）。
   */
  const q = params.get('q') ?? ''
  const [search, setSearch] = useState(q)
  useEffect(() => { setSearch(q) }, [q])
  const keyword = search.trim().toLowerCase()

  const writeSearch = (value: string) => {
    setSearch(value)
    setParams((prev) => {
      const updated = new URLSearchParams(prev)
      if (value.trim()) updated.set('q', value)
      else updated.delete('q')
      return updated
    }, { replace: true })
  }

  const toggleFilter = (next: FilterKey) => {
    setParams((prev) => {
      const current = prev.getAll('state').filter((value) => (FILTERS as string[]).includes(value))
      const values = current.includes(next)
        ? current.filter((value) => value !== next)
        : [...current, next]
      const updated = new URLSearchParams(prev)
      updated.delete('state')
      values.forEach((value) => updated.append('state', value))
      updated.delete('page')
      return updated
    })
  }

  const applyFilters = (next: Record<string, string[]>) => {
    setParams((prev) => {
      const updated = new URLSearchParams(prev)
      updated.delete('state')
      ;(next.state ?? []).forEach((value) => updated.append('state', value))
      updated.delete('page')
      return updated
    })
  }

  const offering = data?.offering
  const progress = data?.progress
  const submissions = data?.submissions ?? []
  const classNames = Object.fromEntries((data?.classes ?? []).map((c) => [c.id, c.name]))

  // 应交人数取后端 expectedCount（各班成员并集，学生带两个班只算一次）。2026-10-26 之前这里按班相加，
  // 同一学生跨两个班会被算两次，导致「已交 1 / 应交 31」永远交不齐；列表页「提交」列已改成同一口径。
  const expected = offering ? offering.expectedCount : 0
  const submittedStudents = new Set(
    submissions.filter((s) => s.submittedAt && !s.rejectedAt).map((s) => s.studentId),
  ).size
  const unsubmitted = Math.max(0, expected - submittedStudents)

  const filtered = submissions.filter((row) => {
    if (keyword) {
      const hit = row.studentName.toLowerCase().includes(keyword)
        || row.studentId.toLowerCase().includes(keyword)
      if (!hit) return false
    }
    if (activeStates.length === 0) return true
    const bucket = bucketOf(row)
    return bucket !== null && activeStates.includes(bucket)
  })

  /** 「清空筛选」= 回到全量：关键字与批改状态筛选一起清（与首页 / 作业列表 / 词库同一个语义）。 */
  const resetAll = () => {
    setSearch('')
    setParams((prev) => {
      const updated = new URLSearchParams(prev)
      ;['q', 'state', 'page'].forEach((key) => updated.delete(key))
      return updated
    }, { replace: true })
  }

  const submittedCount = submissions.filter((row) => Boolean(row.submittedAt)).length
  const rejectedCount = submissions.filter((row) => Boolean(row.rejectedAt)).length
  const gradedCount = (progress?.done ?? 0) + rejectedCount
  const dispatchableCount = submissions.filter(canAiDispatch).length
  const gates = detailGates({
    publishScores: Boolean(offering?.publishScores),
    submitted: submittedCount,
    done: gradedCount,
    dispatchable: dispatchableCount,
  })

  /** 未交名单：按班分组，空态直接告诉老师「该找谁」。 */
  const unsubmittedGroups = (data?.summary ?? [])
    .map((row) => ({
      className: classNames[row.classId] ?? row.classId,
      names: row.unsubmitted.map((student) => student.studentName),
    }))
    .filter((group) => group.names.length > 0)

  // 默认页签随「成绩是否公布」（AC-25.6 修订）：已公布直接落在成绩汇总。
  // 只有没带 `?tab=` 时才算「默认」，否则用户自己点了「提交明细」会被弹回去。
  useEffect(() => {
    if (!offering) return
    if (params.get('tab')) return
    if (defaultTab(offering.publishScores) === 'summary') {
      navigate(`/offerings/${offering.id}/summary`, { replace: true })
    }
  }, [offering, params, navigate])

  /**
   * 四个 AI 动作（全部派发 / 单份派发 / 重试失败 / 生成解析）共用的前置判断：
   * 先问一次 AI 就绪状态，没就绪就一个请求都不发，只把「请联系管理员设置 AI」的提示亮出来。
   * 返回 true 表示已拦截。探测本身失败时 isAIReady() 放行（见 api/ai.ts 的失败放行注释），
   * 真正「没配置不许批」仍由后端 409 守卫兜底。
   */
  const blockIfAiNotReady = async (): Promise<boolean> => {
    if (await isAIReady()) {
      setAiBlocked(false)
      return false
    }
    setAiBlocked(true)
    return true
  }

  /** 公布成绩：学生从此能看到自己的分数（此前只有「已交卷 · 待老师公布」）。 */
  const publishOfferingScores = async () => {
    setPublishingScores(true)
    setPublishScoresError(null)
    setPublishScoresNote(null)
    try {
      await api.post(`/api/offerings/${offeringId}/scores/publish`)
      setPublishScoresNote(t('teacher.detail.publishScoresDone'))
      reload()
    } catch (err) {
      setPublishScoresError(errorMessage(err))
    } finally {
      setPublishingScores(false)
    }
  }

  const dispatch = async () => {
    if (await blockIfAiNotReady()) return
    setDispatching(true)
    setDispatchError(null)
    setDispatchReceipt(null)
    try {
      const receipt = await api.post<GradingDispatchView>(
        `/api/offerings/${offeringId}/grading/dispatch`,
      )
      setDispatchReceipt(receipt)
      reload()
    } catch (err) {
      setDispatchError(errorMessage(err))
    } finally {
      setDispatching(false)
    }
  }

  /** T252：单份「让 AI 先批」——只把这一份推进 AI 队列，不动同一场的其他提交。 */
  const dispatchOneSubmission = async (row: OfferingSubmissionView) => {
    if (await blockIfAiNotReady()) return
    setAiDispatchingId(row.submissionId)
    setAiDispatchError(null)
    setAiDispatchNote(null)
    try {
      await api.post(`/api/offerings/${offeringId}/submissions/${row.submissionId}/grading/dispatch`)
      setAiDispatchNote(t('teacher.detail.aiDispatchDone', { student: row.studentName }))
      reload()
    } catch (err) {
      setAiDispatchError(t('teacher.detail.aiDispatchFailed', { message: errorMessage(err) }))
    } finally {
      setAiDispatchingId(null)
    }
  }

  const retryFailed = async () => {
    // 按钮只按「有没有失败/卡住」禁用；AI 配置判断必须放在 onClick 的第一行。
    if (await blockIfAiNotReady()) return
    setRetrying(true)
    setDispatchError(null)
    try {
      const res = await api.post<{ retried: number; reclaimed: number }>(
        `/api/offerings/${offeringId}/grading/retry-failed`,
      )
      setRetryNote(res)
      reload()
    } catch (err) {
      setDispatchError(errorMessage(err))
    } finally {
      setRetrying(false)
    }
  }

  const generateExplanations = async () => {
    if (await blockIfAiNotReady()) return
    setExplaining(true)
    setExplainError(null)
    setExplainResult(null)
    try {
      setExplainResult(await api.post<ExplanationGenerateResult>(
        `/api/offerings/${offeringId}/explanations/generate?limit=50`))
    } catch (err) {
      setExplainError(errorMessage(err))
    } finally {
      setExplaining(false)
    }
  }

  const startReject = (row: OfferingSubmissionView) => {
    setRejectingId(row.submissionId)
    setRejectReason('')
    setRejectError(null)
  }

  const cancelReject = () => {
    setRejectingId(null)
    setRejectReason('')
    setRejectError(null)
  }

  const submitReject = async (row: OfferingSubmissionView) => {
    if (!rejectReason.trim()) {
      setRejectError(t('teacher.detail.rejectReasonRequired'))
      return
    }
    setRejecting(true)
    setRejectError(null)
    try {
      await api.post(`/api/offerings/${offeringId}/submissions/${row.submissionId}/reject`, {
        reason: rejectReason.trim(),
      })
      cancelReject()
      reload()
    } catch (err) {
      setRejectError(errorMessage(err))
    } finally {
      setRejecting(false)
    }
  }

  const allColumns: Column<OfferingSubmissionView>[] = [
    {
      key: 'student',
      title: t('teacher.detail.colStudent'),
      value: (row) => row.studentName,
      sortable: true,
      render: (row) => (
        <div>
          <b>{row.studentName}</b>
          <div className="small muted mono">{row.studentId.slice(0, 8)}</div>
        </div>
      ),
    },
    {
      key: 'attempt',
      title: t('teacher.detail.colAttempt'),
      value: (row) => row.attemptNo,
      width: '72px',
      sortable: true,
      render: (row) => row.attemptNo,
    },
    {
      key: 'submittedAt',
      title: t('teacher.detail.colSubmittedAt'),
      value: (row) => row.submittedAt ?? '',
      width: '172px',
      sortable: true,
      render: (row) => fmtDateTime(row.submittedAt),
    },
    {
      key: 'late',
      title: t('teacher.detail.colLate'),
      value: (row) => row.lateBySeconds,
      width: '110px',
      sortable: true,
      render: (row) =>
        row.lateBySeconds > 0 ? (
          <Tag tone="amber">{t('teacher.detail.lateBy', { value: fmtLate(row.lateBySeconds) })}</Tag>
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      key: 'state',
      title: t('teacher.detail.colState'),
      value: (row) => row.gradingState,
      width: '104px',
      sortable: true,
      render: (row) => (
        <span
          title={
            row.rejectedAt
              ? `${fmtDateTime(row.rejectedAt)} · ${row.rejectReason || '—'}`
              : undefined
          }
        >
          <SubmissionStateTag
            state={row.gradingState}
            submittedAt={row.submittedAt}
            rejectedAt={row.rejectedAt}
          />
          {row.gradedProvider && (
            <div className="small muted mono" title={t('teacher.detail.gradedByHint')}>
              {row.gradedProvider}
            </div>
          )}
        </span>
      ),
    },
    {
      key: 'objective',
      title: t('teacher.detail.colObjective'),
      value: (row) => Number(row.objectiveScore),
      width: '84px',
      sortable: true,
      render: (row) =>
        row.submittedAt ? <span className="mono">{fmtNum(row.objectiveScore)}</span> : <span className="muted">—</span>,
    },
    {
      key: 'subjective',
      title: t('teacher.detail.colSubjective'),
      value: (row) => Number(row.subjectiveScore),
      width: '84px',
      sortable: true,
      render: (row) =>
        row.submittedAt ? <span className="mono">{fmtNum(row.subjectiveScore)}</span> : <span className="muted">—</span>,
    },
    {
      key: 'total',
      title: t('teacher.detail.colTotal'),
      value: (row) => Number(row.totalScore),
      width: '84px',
      sortable: true,
      render: (row) =>
        row.submittedAt ? <b className="mono">{fmtNum(row.totalScore)}</b> : <span className="muted">—</span>,
    },
    {
      key: 'actions',
      title: t('teacher.common.actions'),
      // 120px = 1 个主按钮 + 「⋯」；250px 时 4 个按钮会折成两行（AC-25.1 / T489）。
      width: '120px',
      value: () => '',
      render: (row) => {
        // D-15：已交卷且未拒收的提交（无论 AI 有没有派发/失败）都能直接进复核页人工判分。
        const canReject = Boolean(row.submittedAt) && !row.rejectedAt
        const canGrade = canReject
        const aiDispatchable = canAiDispatch(row)
        return (
          <div>
            <RowActions
              main={canGrade ? {
                key: 'grade',
                label: t('teacher.detail.grade'),
                onClick: () => navigate(`/review?submissionId=${row.submissionId}`),
              } : undefined}
              items={[
                {
                  key: 'ai',
                  label: aiDispatchingId === row.submissionId
                    ? t('teacher.detail.aiDispatching')
                    : t('teacher.detail.aiDispatch'),
                  disabled: !aiDispatchable || aiDispatchingId === row.submissionId,
                  // AC-25.5：禁用原因写在菜单文案里，不能只靠 title 悬停。
                  reason: aiDispatchable ? undefined : t('teacher.detail.aiDispatchDisabled'),
                  onClick: () => void dispatchOneSubmission(row),
                },
                {
                  key: 'reject',
                  label: t('teacher.detail.reject'),
                  disabled: !canReject,
                  reason: canReject ? undefined : t('teacher.detail.rejectDisabled'),
                  danger: true,
                  onClick: () => startReject(row),
                },
                {
                  key: 'audit',
                  label: t('teacher.common.audit'),
                  onClick: () => setAuditTarget(row),
                },
              ]}
            />
            {rejectingId === row.submissionId && (
              <div style={{ marginTop: 10 }}>
                <Field
                  label={t('teacher.detail.rejectReasonLabel')}
                  error={rejectError}
                  htmlFor={`reject-${row.submissionId}`}
                >
                  <Input
                    id={`reject-${row.submissionId}`}
                    value={rejectReason}
                    status={rejectError ? 'error' : undefined}
                    onChange={(e) => setRejectReason(e.target.value)}
                    placeholder={t('teacher.detail.rejectReasonPlaceholder')}
                  />
                </Field>
                <div className="row" style={{ gap: 6 }}>
                  <Button size="small" variant="danger" busy={rejecting} onClick={() => void submitReject(row)}>
                    {t('teacher.common.confirm')}
                  </Button>
                  <Button size="small" variant="text" onClick={cancelReject}>
                    {t('teacher.common.cancel')}
                  </Button>
                </div>
              </div>
            )}
          </div>
        )
      },
    },
  ]

  const columns = colPrefs.visible
    .map((key) => allColumns.find((column) => column.key === key))
    .filter((column): column is Column<OfferingSubmissionView> => Boolean(column))

  return (
    <TeacherPage>
      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data && offering && progress && (
        <>
          <PageHeader
            title={offering.paperTitle}
            sub={t('teacher.detail.sub', {
              classes: offering.classes.map((c) => classNames[c.classId] ?? c.classId).join('、'),
              count: expected,
              due: fmtDateTime(offering.dueAt),
            })}
            actions={
              <>
                <Button
                  size="small"
                  onClick={() => void retryFailed()}
                  busy={retrying}
                  disabled={progress.failed === 0 && progress.running === 0}
                  title={t('teacher.detail.retryHint')}
                >
                  {t('teacher.detail.retryFailed')}
                </Button>
                <Button
                  size="small"
                  onClick={() => void generateExplanations()}
                  busy={explaining}
                  disabled={!gates.canExplain}
                  title={gates.canExplain ? undefined : t('teacher.detail.explainDisabled')}
                >
                  {t('teacher.detail.generateExplanations')}
                </Button>
                <Button
                  size="small"
                  variant="text"
                  disabled={!gates.canReview}
                  title={gates.canReview ? undefined : t('teacher.detail.reviewDisabled')}
                  onClick={() => navigate(`/review?offeringId=${offering.id}`)}
                >
                  {t('teacher.detail.toReview')}
                </Button>
                {!offering.publishScores && (
                  <Button
                    size="small"
                    onClick={() => void publishOfferingScores()}
                    busy={publishingScores}
                    disabled={!gates.canPublish}
                    title={gates.canPublish ? t('teacher.detail.publishScoresHint') : t('teacher.detail.publishDisabled')}
                  >
                    {t('teacher.detail.publishScores')}
                  </Button>
                )}
                <Button
                  variant="primary"
                  onClick={() => void dispatch()}
                  busy={dispatching}
                  disabled={!gates.canDispatch}
                  title={gates.canDispatch ? t('teacher.detail.dispatchHint') : t('teacher.detail.dispatchDisabled')}
                >
                  {t('teacher.detail.dispatch')}
                </Button>
              </>
            }
          />

          {/* AC-25.6（2026-10-26 修订）：换成带下划线的真页签 + 计数/角标；上一版 Segmented 用户说「根本没看到」。 */}
          <ViewTabs
            selected="submissions"
            items={[
              {
                key: 'submissions',
                label: t('teacher.detail.viewSubmissions'),
                to: `/grading/${offering.id}?tab=submissions`,
                badge: <span className="tl-tab-count">{submissions.length}</span>,
              },
              {
                key: 'summary',
                label: t('teacher.detail.viewSummary'),
                to: `/offerings/${offering.id}/summary`,
                badge: offering.publishScores
                  ? undefined
                  : <span className="tl-tab-badge">{t('teacher.detail.summaryPreview')}</span>,
              },
            ]}
          />

          <AiCircuitBanner />

          {aiReadiness.view && !aiReadiness.view.ready && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={t('teacher.detail.aiNotConfiguredHint')}
              action={aiSetupTarget
                ? <Button size="small" onClick={() => navigate(aiSetupTarget)}>{t('teacher.detail.goSetAi')}</Button>
                : undefined}
            />
          )}

          {offering.status === 'DRAFT' && <Alert type="warning" showIcon message={t('teacher.detail.draftNote')} />}
          {/* 没有提交时这条「AI 什么时候批」的说明没有对象可批，纯噪音。 */}
          {!gates.noSubmission && offering.kind === 'ASSIGNMENT' && (
            <div className="inline-note">{t('teacher.detail.nightlyGrading')}</div>
          )}

          {/* 没人交卷时不再摆 4 张 0 卡（用户要求）：所有计数器都是 0，占掉半屏零信息。 */}
          {!gates.noSubmission && (
            <div className="stats">
              <StatCard
                value={progress.done}
                label={t('teacher.detail.stats.done')}
                active={activeStates.includes('done')}
                onClick={() => toggleFilter('done')}
              />
              <StatCard
                value={progress.running}
                label={t('teacher.detail.stats.running')}
                active={activeStates.includes('running')}
                onClick={() => toggleFilter('running')}
              />
              <StatCard
                value={progress.pending}
                label={t('teacher.detail.stats.pending')}
                active={activeStates.includes('pending')}
                onClick={() => toggleFilter('pending')}
              />
              <StatCard
                value={progress.failed}
                label={t('teacher.detail.stats.failed')}
                active={activeStates.includes('failed')}
                onClick={() => toggleFilter('failed')}
              />
            </div>
          )}

          <div className="card">
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <div className="row">
                <Tag tone={offering.status === 'PUBLISHED' ? 'green' : 'gray'}>
                  {t(`teacher.offeringStatus.${offering.status}`)}
                </Tag>
                <Tag tone="blue">{t(`teacher.kind.${offering.kind}`)}</Tag>
                <span className="small muted">
                  {t('teacher.detail.submittedSummary', { submitted: submittedStudents, expected })}
                </span>
                {/* 两个「发布」必须并排说清：作业已发布 ≠ 成绩已公布（2026-10-26 用户指出）。 */}
                <Tag tone={offering.publishScores ? 'green' : 'gray'}>
                  {offering.publishScores
                    ? t('teacher.detail.publishedScores')
                    : t('teacher.detail.unpublishedScores')}
                </Tag>
                {offering.kind === 'EXAM' && (
                  <span className="small muted">
                    {t('teacher.detail.duration', { minutes: offering.effectiveDurationMinutes })}
                  </span>
                )}
              </div>
            </div>

            {/* 没人交卷：这一屏唯一的有效信息是「谁没交」，直接用 class-summary 列出来。 */}
            {gates.noSubmission && (
              <div style={{ marginTop: 12 }}>
                <h3 style={{ margin: '0 0 4px' }}>{t('teacher.detail.noSubmissionTitle')}</h3>
                <p className="muted" style={{ margin: 0 }}>
                  {t('teacher.detail.noSubmissionHint', { n: unsubmitted, due: fmtDateTime(offering.dueAt) })}
                </p>
                {unsubmittedGroups.length > 0 && (
                  <div style={{ marginTop: 10 }}>
                    <div className="small muted" style={{ marginBottom: 6 }}>
                      {t('teacher.detail.unsubmittedListTitle')}
                    </div>
                    {unsubmittedGroups.map((group) => (
                      <div key={group.className} className="small" style={{ marginBottom: 4 }}>
                        <b>{group.className}</b>
                        <span className="muted"> · {group.names.join('、')}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {explainError && (
              <Alert type="error" showIcon style={{ marginTop: 12 }} message={explainError} />
            )}
            {explainResult && (
              <Alert
                type={explainResult.failed > 0 ? 'warning' : 'success'}
                showIcon
                style={{ marginTop: 12 }}
                message={t('teacher.detail.explanationReceipt')}
                description={
                  <>
                    {t('teacher.detail.explanationCounts', {
                      generated: explainResult.generated,
                      existing: explainResult.skippedExisting,
                      noStem: explainResult.skippedNoStem,
                      failed: explainResult.failed,
                    })}
                    {explainResult.failed > 0 && <div>{t('teacher.detail.explanationFailedHint')}</div>}
                  </>
                }
              />
            )}
            {retryNote && (
              <Alert
                type="info"
                showIcon
                style={{ marginTop: 10 }}
                message={t('teacher.detail.retryDone', { retried: retryNote.retried, reclaimed: retryNote.reclaimed })}
              />
            )}
            {aiDispatchNote && <Alert type="success" showIcon style={{ marginTop: 10 }} message={aiDispatchNote} />}
            {aiDispatchError && <Alert type="error" showIcon style={{ marginTop: 10 }} message={aiDispatchError} />}
            {aiBlocked && (
              <Alert
                type="error"
                showIcon
                style={{ marginTop: 10 }}
                message={t('teacher.detail.aiNotConfiguredBlocked')}
                action={aiSetupTarget
                  ? <Button size="small" onClick={() => navigate(aiSetupTarget)}>{t('teacher.detail.goSetAi')}</Button>
                  : undefined}
              />
            )}
            {publishScoresError && (
              <Alert
                type="error"
                showIcon
                style={{ marginTop: 10 }}
                message={t('teacher.detail.publishScoresFailed', { message: publishScoresError })}
              />
            )}
            {publishScoresNote && (
              <Alert type="success" showIcon style={{ marginTop: 10 }} message={publishScoresNote} />
            )}
            {dispatchError && (
              <Alert
                type="error"
                showIcon
                style={{ marginTop: 10 }}
                message={t('teacher.detail.dispatchFailed', { message: dispatchError })}
              />
            )}
            {dispatchReceipt && (
              <Alert
                type="success"
                showIcon
                style={{ marginTop: 10 }}
                message={t('teacher.detail.dispatchReceipt')}
                description={
                  <>
                    <div className="small mono">runId: {dispatchReceipt.runId}</div>
                    <div className="small">
                      {t('teacher.detail.receiptFirstDispatch', {
                        value: dispatchReceipt.firstDispatch ? t('teacher.common.yes') : t('teacher.common.no'),
                      })}
                      {' · '}
                      {t('teacher.detail.receiptEnqueued', { n: dispatchReceipt.enqueued })}
                    </div>
                  </>
                }
              />
            )}
          </div>

          {/* 表格标题不再另起「逐份状态」：页签已经说明了这是「提交明细」（用户要求 tab 与内容同名）。 */}
          {!gates.noSubmission && (
            <DataTable
              rows={filtered}
              columns={columns}
              rowKey={(row) => row.submissionId}
              sort={sort}
              page={page}
              pageSize={pageSize}
              onSortChange={setSort}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
              toolbar={(
                <>
                  <Search
                    className="tl-table-search"
                    value={search}
                    allowClear
                    placeholder={t('teacher.detail.searchPlaceholder')}
                    onChange={(event) => writeSearch(event.target.value)}
                    onSearch={(value) => writeSearch(value)}
                  />
                  <TableToolbar
                    groups={[{
                      key: 'state',
                      label: t('teacher.detail.filterStates'),
                      options: FILTERS.map((key) => ({ value: key, label: t(`teacher.detail.filters.${key}`) })),
                    }]}
                    value={{ state: activeStates }}
                    onChange={applyFilters}
                    onReset={resetAll}
                    columnPicker={<Button onClick={() => setColumnsOpen(true)}>{t('common.columns')}</Button>}
                    summary={t('teacher.detail.filteredCount', { shown: filtered.length, total: submissions.length })}
                  />
                </>
              )}
              empty={
                keyword
                  ? t('teacher.detail.noMatch')
                  : activeStates.length > 0
                    ? t('teacher.detail.filteredEmpty')
                    : t('teacher.detail.empty')
              }
            />
          )}

          <AuditDrawer
            open={auditTarget !== null}
            onClose={() => setAuditTarget(null)}
            targetType="submission"
            targetId={auditTarget?.submissionId ?? ''}
            title={auditTarget ? `${auditTarget.studentName} · ${t('teacher.common.audit')}` : undefined}
          />

          <ColumnPicker
            open={columnsOpen}
            onClose={() => setColumnsOpen(false)}
            order={colPrefs.order}
            hidden={colPrefs.hidden}
            labels={Object.fromEntries(COLUMN_ORDER.map((key) => [key, t(COLUMN_LABEL_KEY[key])]))}
            onToggle={colPrefs.toggle}
            onMove={colPrefs.move}
          />
        </>
      )}
    </TeacherPage>
  )
}

/** T252：只有「待派发（QUEUED）/ 上次失败（FAILED）」且已交卷未拒收的提交可以先跑 AI。 */
function canAiDispatch(row: OfferingSubmissionView): boolean {
  if (!row.submittedAt || row.rejectedAt) return false
  return row.gradingState === 'QUEUED' || row.gradingState === 'FAILED'
}

/** 一行提交属于哪个筛选档；不认识的批改状态返回 null（不参与任何一档）。 */
function bucketOf(row: OfferingSubmissionView): FilterKey | null {
  if (row.rejectedAt) return 'rejected'
  switch (row.gradingState) {
    case 'DONE':
      return 'done'
    case 'RUNNING':
      return 'running'
    case 'FAILED':
      return 'failed'
    case 'PENDING':
    case 'QUEUED':
      return 'pending'
    default:
      return null
  }
}
