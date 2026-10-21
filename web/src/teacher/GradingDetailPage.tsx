import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router-dom'
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
import { DataTable } from '../components/DataTable'
import type { Column } from '../components/DataTable'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { useListState } from '../components/useListState'
import {
  errorMessage,
  fmtLate,
  fmtNum,
  PageSizePicker,
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
 */
export default function GradingDetailPage() {
  const { t } = useTranslation('translation')
  const { offeringId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const { sort, page, pageSize, setSort, setPage } = useListState(
    { key: 'submittedAt', dir: 'desc' },
    20,
  )

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

  const dispatch = async () => {
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
      sortable: true,
      render: (row) => row.attemptNo,
    },
    {
      key: 'submittedAt',
      title: t('teacher.detail.colSubmittedAt'),
      value: (row) => row.submittedAt ?? '',
      sortable: true,
      render: (row) => fmtDateTime(row.submittedAt),
    },
    {
      key: 'late',
      title: t('teacher.detail.colLate'),
      value: (row) => row.lateBySeconds,
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
      sortable: true,
      render: (row) => (
        <SubmissionStateTag
          state={row.gradingState}
          submittedAt={row.submittedAt}
          rejectedAt={row.rejectedAt}
        />
      ),
    },
    {
      key: 'objective',
      title: t('teacher.detail.colObjective'),
      value: (row) => Number(row.objectiveScore),
      sortable: true,
      render: (row) =>
        row.submittedAt ? (
          <span className="mono">{fmtNum(row.objectiveScore)}</span>
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      key: 'subjective',
      title: t('teacher.detail.colSubjective'),
      value: (row) => Number(row.subjectiveScore),
      sortable: true,
      render: (row) =>
        row.submittedAt ? (
          <span className="mono">{fmtNum(row.subjectiveScore)}</span>
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      key: 'total',
      title: t('teacher.detail.colTotal'),
      value: (row) => Number(row.totalScore),
      sortable: true,
      render: (row) =>
        row.submittedAt ? (
          <b className="mono">{fmtNum(row.totalScore)}</b>
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      key: 'reject',
      title: t('teacher.detail.colReject'),
      value: (row) => row.rejectReason ?? '',
      sortable: true,
      render: (row) =>
        row.rejectedAt ? (
          <div>
            <div className="small mono">{fmtDateTime(row.rejectedAt)}</div>
            <div className="small muted">{row.rejectReason || '—'}</div>
          </div>
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      key: 'actions',
      title: t('teacher.common.actions'),
      value: () => '',
      render: (row) => {
        // D-15：已交卷且未拒收的提交（无论 AI 有没有派发/失败）都能直接进复核页人工判分。
        const canReject = Boolean(row.submittedAt) && !row.rejectedAt
        const canGrade = canReject
        return (
          <div style={{ minWidth: 280 }}>
            <div className="row" style={{ gap: 6 }}>
              <button
                type="button"
                className="btn sm"
                disabled={!canReject}
                title={canReject ? undefined : t('teacher.detail.rejectDisabled')}
                onClick={() => startReject(row)}
              >
                {t('teacher.detail.reject')}
              </button>
              <button
                type="button"
                className="btn sm"
                disabled={!canAiDispatch(row) || aiDispatchingId === row.submissionId}
                title={canAiDispatch(row) ? t('teacher.detail.aiDispatchHint') : t('teacher.detail.aiDispatchDisabled')}
                onClick={() => void dispatchOneSubmission(row)}
              >
                {aiDispatchingId === row.submissionId ? t('teacher.common.loading') : t('teacher.detail.aiDispatch')}
              </button>
              <button
                type="button"
                className="btn sm"
                onClick={() => setAuditTarget(row)}
              >
                {t('teacher.common.audit')}
              </button>
              {canGrade && (
                <Link className="btn sm" to={`/review?submissionId=${row.submissionId}`}>
                  {t('teacher.detail.grade')}
                </Link>
              )}
            </div>
            {rejectingId === row.submissionId && (
              <div style={{ marginTop: 8 }}>
                <label className="small" htmlFor={`reject-${row.submissionId}`}>
                  {t('teacher.detail.rejectReasonLabel')}
                </label>
                <input
                  id={`reject-${row.submissionId}`}
                  value={rejectReason}
                  onChange={(e) => setRejectReason(e.target.value)}
                  placeholder={t('teacher.detail.rejectReasonPlaceholder')}
                  style={{ marginTop: 4 }}
                />
                {rejectError && (
                  <div className="hint" style={{ color: 'var(--red)' }}>
                    {rejectError}
                  </div>
                )}
                <div className="row" style={{ gap: 6, marginTop: 6 }}>
                  <button
                    type="button"
                    className="btn sm pri"
                    disabled={rejecting}
                    onClick={() => void submitReject(row)}
                  >
                    {rejecting ? t('teacher.common.saving') : t('teacher.common.confirm')}
                  </button>
                  <button type="button" className="btn sm" onClick={cancelReject}>
                    {t('teacher.common.cancel')}
                  </button>
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
          />

          {offering.status === 'DRAFT' && (
            <div className="inline-note">{t('teacher.detail.draftNote')}</div>
          )}

          <div className="card">
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div>
                <div className="stat-row">
                  <Tag tone="green">
                    {t('teacher.detail.statDone', { n: progress.done })}
                  </Tag>
                  <Tag tone={progress.running > 0 ? 'blue' : 'gray'}>
                    {t('teacher.detail.statRunning', { n: progress.running })}
                  </Tag>
                  <Tag tone={progress.pending > 0 ? 'amber' : 'gray'}>
                    {t('teacher.detail.statPending', { n: progress.pending })}
                  </Tag>
                  <Tag tone={progress.failed > 0 ? 'red' : 'gray'}>
                    {t('teacher.detail.statFailed', { n: progress.failed })}
                  </Tag>
                  <Tag tone="gray">{t('teacher.detail.statUnsubmitted', { n: unsubmitted })}</Tag>
                  <Tag tone={offering.status === 'PUBLISHED' ? 'green' : 'gray'}>
                    {t(`teacher.offeringStatus.${offering.status}`)}
                  </Tag>
                  <Tag tone="blue">{t(`teacher.kind.${offering.kind}`)}</Tag>
                </div>
                <div className="hint">
                  {t('teacher.detail.submittedSummary', {
                    submitted: submittedStudents,
                    expected,
                  })}
                  {offering.kind === 'EXAM' && (
                    <>
                      {' · '}
                      {t('teacher.detail.duration', { minutes: offering.effectiveDurationMinutes })}
                    </>
                  )}
                </div>
              </div>
              <div className="row" style={{ gap: 6 }}>
                <button
                  type="button"
                  className="btn sm"
                  disabled={retrying || (progress.failed === 0 && progress.running === 0)}
                  title={t('teacher.detail.retryHint')}
                  onClick={() => void retryFailed()}
                >
                  {retrying ? t('teacher.common.loading') : t('teacher.detail.retryFailed')}
                </button>
                <button
                  type="button"
                  className="btn sm"
                  disabled={dispatching}
                  title={t('teacher.detail.dispatchHint')}
                  onClick={() => void dispatch()}
                >
                  {dispatching ? t('teacher.common.loading') : t('teacher.detail.dispatch')}
                </button>
                <button
                  type="button"
                  className="btn sm"
                  disabled={explaining}
                  onClick={() => void generateExplanations()}
                >
                  {explaining ? t('teacher.common.loading') : t('teacher.detail.generateExplanations')}
                </button>
                <Link className="btn sm" to={`/review?offeringId=${offering.id}`}>
                  {t('teacher.detail.toReview')}
                </Link>
                <Link className="btn sm" to={`/offerings/${offering.id}/summary`}>
                  {t('teacher.common.summary')}
                </Link>
              </div>
            </div>


            {explainError && (
              <div className="hint" style={{ color: 'var(--red)' }}>{explainError}</div>
            )}
            {explainResult && (
              <div className="inline-note" style={{ marginTop: 12 }}>
                <b>{t('teacher.detail.explanationReceipt')}</b>
                <div className="small">
                  {t('teacher.detail.explanationCounts', {
                    generated: explainResult.generated,
                    existing: explainResult.skippedExisting,
                    noStem: explainResult.skippedNoStem,
                    failed: explainResult.failed,
                  })}
                </div>
                {explainResult.failed > 0 && (
                  <div className="small muted">{t('teacher.detail.explanationFailedHint')}</div>
                )}
              </div>
            )}
            {retryNote && (
              <div className="inline-note" style={{ marginTop: 8 }}>
                {t('teacher.detail.retryDone', { retried: retryNote.retried, reclaimed: retryNote.reclaimed })}
              </div>
            )}
            {aiDispatchNote && (
              <div className="inline-note" style={{ marginTop: 10 }}>{aiDispatchNote}</div>
            )}
            {aiDispatchError && (
              <div className="hint" style={{ color: 'var(--red)' }}>{aiDispatchError}</div>
            )}
            {dispatchError && (
              <div className="hint" style={{ color: 'var(--red)' }}>
                {t('teacher.detail.dispatchFailed', { message: dispatchError })}
              </div>
            )}
            {dispatchReceipt && (
              <div className="inline-note ok" style={{ marginTop: 12 }}>
                <b>{t('teacher.detail.dispatchReceipt')}</b>
                <div className="small mono">runId: {dispatchReceipt.runId}</div>
                <div className="small">
                  {t('teacher.detail.receiptFirstDispatch', {
                    value: dispatchReceipt.firstDispatch
                      ? t('teacher.common.yes')
                      : t('teacher.common.no'),
                  })}
                  {' · '}
                  {t('teacher.detail.receiptEnqueued', { n: dispatchReceipt.enqueued })}
                </div>
              </div>
            )}
          </div>

          <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
            <h2 style={{ margin: 0 }}>
              {t('teacher.detail.tableTitle')}{' '}
              <small className="muted">{t('teacher.detail.tableSub')}</small>
            </h2>
            <PageSizePicker pageSize={pageSize} />
          </div>

          <div className="card" style={{ padding: '10px 14px' }}>
            <div className="row">
              <span className="small muted">{t('teacher.detail.filterLabel')}</span>
              {FILTERS.map((key) => (
                <button
                  key={key}
                  type="button"
                  className={`chip${filter === key ? ' on' : ''}`}
                  onClick={() => setFilter(key)}
                >
                  {t(`teacher.detail.filters.${key}`)}
                </button>
              ))}
              <span className="small muted" style={{ marginLeft: 'auto' }}>
                {t('teacher.detail.filteredCount', {
                  shown: filtered.length,
                  total: submissions.length,
                })}
              </span>
            </div>
            <div className="hint" style={{ marginTop: 4 }}>
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
