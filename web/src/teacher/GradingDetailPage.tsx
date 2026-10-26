import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { isAIReady } from '../api/ai'
import { useAuth } from '../api/auth'
import { api, fmtDateTime } from '../api/client'
import type {
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
import { Alert, Button, DataTable, Empty, ErrorState, Field, Input, Loading, PageHeader, StatCard, Segmented } from '../ui'
import type { Column } from '../ui'
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
}

type FilterKey = 'all' | 'done' | 'running' | 'pending' | 'failed' | 'rejected'
const FILTERS: FilterKey[] = ['all', 'done', 'running', 'pending', 'failed', 'rejected']

/**
 * T182 / AC-20.5–AC-20.7：逐份提交表。
 * 派发回执直接显示在页面；拒收走行内表单（不使用 prompt/confirm）；
 * 「重试失败项」只把 FAILED 退回队列，运行中与已完成的不动。
 *
 * 2026-10-21（UI 标准）：顶部七枚标签改成 4 张可点统计卡（点数字即过滤），
 * 5 个操作按钮按主次分到页头；回执与错误改用 Alert；拒收表单用 Field。
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
    const [offering, progress, submissions, classes] = await Promise.all([
      api.get<OfferingView>(`/api/offerings/${offeringId}`),
      api.get<ProgressView>(`/api/offerings/${offeringId}/progress`),
      api.get<OfferingSubmissionView[]>(`/api/offerings/${offeringId}/submissions`),
      api.get<ClassView[]>('/api/classes'),
    ])
    return { offering, progress, submissions, classes }
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
  const [auditTarget, setAuditTarget] = useState<OfferingSubmissionView | null>(null)

  const filter = normalizeFilter(params.get('state'))
  const setFilter = (next: FilterKey) => {
    setParams((prev) => {
      const updated = new URLSearchParams(prev)
      if (next === 'all') updated.delete('state')
      else updated.set('state', next)
      updated.delete('page')
      return updated
    })
  }

  const offering = data?.offering
  const progress = data?.progress
  const submissions = data?.submissions ?? []
  const classNames = Object.fromEntries((data?.classes ?? []).map((c) => [c.id, c.name]))

  const expected = offering
    ? offering.classes.reduce((acc, c) => acc + Number(c.memberCount), 0)
    : 0
  const submittedStudents = new Set(
    submissions.filter((s) => s.submittedAt && !s.rejectedAt).map((s) => s.studentId),
  ).size
  const unsubmitted = Math.max(0, expected - submittedStudents)

  const filtered = submissions.filter((row) => matchesFilter(row, filter))

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

  const columns: Column<OfferingSubmissionView>[] = [
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
      width: '250px',
      value: () => '',
      render: (row) => {
        // D-15：已交卷且未拒收的提交（无论 AI 有没有派发/失败）都能直接进复核页人工判分。
        const canReject = Boolean(row.submittedAt) && !row.rejectedAt
        const canGrade = canReject
        return (
          <div>
            <div className="row" style={{ gap: 6 }}>
              {canGrade && (
                <Button size="small" variant="primary" onClick={() => navigate(`/review?submissionId=${row.submissionId}`)}>
                  {t('teacher.detail.grade')}
                </Button>
              )}
              <Button
                size="small"
                disabled={!canAiDispatch(row) || aiDispatchingId === row.submissionId}
                title={canAiDispatch(row) ? t('teacher.detail.aiDispatchHint') : t('teacher.detail.aiDispatchDisabled')}
                onClick={() => void dispatchOneSubmission(row)}
              >
                {aiDispatchingId === row.submissionId ? t('teacher.detail.aiDispatching') : t('teacher.detail.aiDispatch')}
              </Button>
              <Button
                size="small"
                variant="text"
                disabled={!canReject}
                title={canReject ? undefined : t('teacher.detail.rejectDisabled')}
                onClick={() => startReject(row)}
              >
                {t('teacher.detail.reject')}
              </Button>
              <Button size="small" variant="text" onClick={() => setAuditTarget(row)}>
                {t('teacher.common.audit')}
              </Button>
            </div>
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

  return (
    <TeacherPage>
      <div className="breadcrumb">
        <Link to="/grading">{t('teacher.grading.title')}</Link> /{' '}
        <b>{offering?.paperTitle ?? t('teacher.detail.title')}</b>
      </div>

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data && offering && progress && (
        <>
          <PageHeader
            title={offering.paperTitle}
            sub={t('teacher.detail.sub', {
              classes: offering.classes
                .map((c) => classNames[c.classId] ?? c.classId)
                .join('、'),
              count: expected,
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
                >
                  {t('teacher.detail.generateExplanations')}
                </Button>
                <Button size="small" variant="text" onClick={() => navigate(`/review?offeringId=${offering.id}`)}>
                  {t('teacher.detail.toReview')}
                </Button>
                <Button size="small" variant="text" onClick={() => navigate(`/offerings/${offering.id}/summary`)}>
                  {t('teacher.common.summary')}
                </Button>
                <Button
                  variant="primary"
                  onClick={() => void dispatch()}
                  busy={dispatching}
                  title={t('teacher.detail.dispatchHint')}
                >
                  {t('teacher.detail.dispatch')}
                </Button>
              </>
            }
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
          {offering.kind === 'ASSIGNMENT' && (
            <div className="inline-note">{t('teacher.detail.nightlyGrading')}</div>
          )}

          <div className="stats">
            <StatCard
              value={progress.done}
              label={t('teacher.detail.stats.done')}
              active={filter === 'done'}
              onClick={() => setFilter(filter === 'done' ? 'all' : 'done')}
            />
            <StatCard
              value={progress.running}
              label={t('teacher.detail.stats.running')}
              active={filter === 'running'}
              onClick={() => setFilter(filter === 'running' ? 'all' : 'running')}
            />
            <StatCard
              value={progress.pending}
              label={t('teacher.detail.stats.pending')}
              active={filter === 'pending'}
              onClick={() => setFilter(filter === 'pending' ? 'all' : 'pending')}
            />
            <StatCard
              value={progress.failed}
              label={t('teacher.detail.stats.failed')}
              active={filter === 'failed'}
              onClick={() => setFilter(filter === 'failed' ? 'all' : 'failed')}
            />
          </div>

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
                {offering.kind === 'EXAM' && (
                  <span className="small muted">
                    {t('teacher.detail.duration', { minutes: offering.effectiveDurationMinutes })}
                  </span>
                )}
                <span className="small muted">{t('teacher.detail.statUnsubmitted', { n: unsubmitted })}</span>
              </div>
            </div>

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

          <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
            <h2 style={{ margin: 0 }}>
              {t('teacher.detail.tableTitle')}{' '}
              <small className="muted">{t('teacher.detail.tableSub')}</small>
            </h2>
          </div>

          <div className="card" style={{ padding: '10px 14px' }}>
            <div className="row">
              <span className="small muted">{t('teacher.detail.filterLabel')}</span>
              <Segmented
                size="small"
                value={filter}
                onChange={(value) => setFilter(value as FilterKey)}
                options={FILTERS.map((key) => ({ value: key, label: t(`teacher.detail.filters.${key}`) }))}
              />
              <span className="small muted" style={{ marginLeft: 'auto' }}>
                {t('teacher.detail.filteredCount', { shown: filtered.length, total: submissions.length })}
              </span>
            </div>
            <div className="hint" style={{ marginTop: 6 }}>
              {t('teacher.detail.filterHint')}
            </div>
          </div>

          {submissions.length === 0 ? (
            <Empty>{t('teacher.detail.empty')}</Empty>
          ) : (
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
              empty={t('teacher.detail.filteredEmpty')}
            />
          )}

          <AuditDrawer
            open={auditTarget !== null}
            onClose={() => setAuditTarget(null)}
            targetType="submission"
            targetId={auditTarget?.submissionId ?? ''}
            title={auditTarget ? `${auditTarget.studentName} · ${t('teacher.common.audit')}` : undefined}
          />
        </>
      )}
    </TeacherPage>
  )
}

function normalizeFilter(raw: string | null): FilterKey {
  if (raw === 'done' || raw === 'running' || raw === 'pending' || raw === 'failed' || raw === 'rejected') {
    return raw
  }
  return 'all'
}

/** T252：只有「待派发（QUEUED）/ 上次失败（FAILED）」且已交卷未拒收的提交可以先跑 AI。 */
function canAiDispatch(row: OfferingSubmissionView): boolean {
  if (!row.submittedAt || row.rejectedAt) return false
  return row.gradingState === 'QUEUED' || row.gradingState === 'FAILED'
}

function matchesFilter(row: OfferingSubmissionView, filter: FilterKey): boolean {
  if (filter === 'all') return true
  if (filter === 'rejected') return Boolean(row.rejectedAt)
  if (row.rejectedAt) return false
  switch (filter) {
    case 'done':
      return row.gradingState === 'DONE'
    case 'running':
      return row.gradingState === 'RUNNING'
    case 'pending':
      return row.gradingState === 'PENDING' || row.gradingState === 'QUEUED'
    case 'failed':
      return row.gradingState === 'FAILED'
    default:
      return true
  }
}
