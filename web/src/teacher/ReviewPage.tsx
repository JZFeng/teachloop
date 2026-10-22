import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { api, fmtDateTime } from '../api/client'
import { errorMessage, fmtNum } from './common'
import type {
  BulkApprovePreview,
  BulkApproveResult,
  ReviewAnswer,
  ReviewDetail,
  ReviewItem,
  ReviewView,
} from '../api/types'
import { AuditDrawer } from '../components/AuditDrawer'
import { Markdown } from '../components/Markdown'
import { Button, DataTable, Empty, ErrorState, Loading, PageHeader, StatCard } from '../ui'
import type { Column } from '../ui'

/** 一道题在复核页上的展示状态：答对、部分对、答错、不自动判分。 */
type AnswerTone = 'correct' | 'partial' | 'wrong' | 'ungraded'

interface ReviewRow {
  item: ReviewItem
  answer: ReviewAnswer | undefined
  tone: AnswerTone
  awarded: number | null
}

function isAutoGraded(item: ReviewItem): boolean {
  return item.questionType !== 'ESSAY' && item.answerKey != null
}

/**
 * 把题项、作答与判分结果合成表格行。
 *
 * <p>对错以服务端返回的 awardedPoints 为准（部分给分也在这条口径里）；
 * 客观题没有作答行时按 0 分算，避免「没答」被误显示成「待评」。
 */
function reviewRowsOf(detail: ReviewDetail): ReviewRow[] {
  const answers = new Map(detail.answers.map((a) => [a.number, a]))
  return detail.items.map((item) => {
    const answer = answers.get(item.number)
    if (!isAutoGraded(item)) {
      return { item, answer, tone: 'ungraded', awarded: null }
    }
    const awarded = answer?.awardedPoints == null ? 0 : Number(answer.awardedPoints)
    const full = Number(item.points)
    const tone: AnswerTone = awarded <= 0 ? 'wrong' : awarded >= full ? 'correct' : 'partial'
    return { item, answer, tone, awarded }
  })
}

function toneTagClass(tone: AnswerTone): string {
  if (tone === 'correct') return 'green'
  if (tone === 'partial') return 'amber'
  if (tone === 'wrong') return 'red'
  return 'gray'
}

function displayAnswer(answer: ReviewAnswer | undefined): string {
  const text = answer?.answer?.trim()
  return text ? text : '—'
}

export default function ReviewPage() {
  const { t } = useTranslation('translation')

  // T244：/review?submissionId=xxx 直达。URL 里的 id 优先——即使它不在复核队列里
  // （已通过、已发布、未派发 AI 的提交）也要能打开，刷新后仍然停在同一个人。
  const [searchParams, setSearchParams] = useSearchParams()
  const urlSubmissionId = searchParams.get('submissionId')
  const urlIdRef = useRef<string | null>(urlSubmissionId)
  urlIdRef.current = urlSubmissionId

  const [queue, setQueue] = useState<ReviewView[] | null>(null)
  const [queueError, setQueueError] = useState<Error | null>(null)
  const [selected, setSelected] = useState<string | null>(() => urlSubmissionId)
  const [detail, setDetail] = useState<ReviewDetail | null>(null)
  const [detailError, setDetailError] = useState<Error | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [batch, setBatch] = useState<BulkApproveResult | null>(null)
  const [batchPreview, setBatchPreview] = useState<BulkApprovePreview | null>(null)
  const [auditFor, setAuditFor] = useState<string | null>(null)
  const [form, setForm] = useState({ subjectivePoints: '', comment: '', strengths: '', suggestions: '', reason: '' })

  const loadQueue = useCallback(() => {
    setQueueError(null)
    api.get<ReviewView[]>('/api/grading/queue')
      .then((rows) => {
        setQueue(rows)
        setSelected((prev) => {
          const fromUrl = urlIdRef.current
          if (fromUrl) return fromUrl
          if (prev) return prev
          return rows.length > 0 ? rows[0].submissionId : null
        })
      })
      .catch((e: Error) => setQueueError(e))
  }, [])

  useEffect(() => { loadQueue() }, [loadQueue])

  // 浏览器前进/后退、或别的页面写进 URL 的 submissionId：跟着 URL 切换详情。
  useEffect(() => {
    if (urlSubmissionId && urlSubmissionId !== selected) setSelected(urlSubmissionId)
  }, [urlSubmissionId, selected])

  /** 选中一份提交并把 submissionId 写进 URL（replace，不污染后退历史）。 */
  const openSubmission = (submissionId: string) => {
    setSelected(submissionId)
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.set('submissionId', submissionId)
      return next
    }, { replace: true })
  }

  const loadDetail = useCallback(() => {
    if (!selected) { setDetail(null); return }
    setDetailError(null)
    setActionError(null)
    api.get<ReviewDetail>(`/api/submissions/${selected}/review`)
      .then((d) => {
        setDetail(d)
        const teacherSaved = d.review.teacherId != null
        setForm({
          // D-20：AI 草稿预填分数与三段文本；教师改过后以教师字段为准。
          subjectivePoints:
            d.aiSubjectiveDraft != null && !teacherSaved
              ? String(d.aiSubjectiveDraft)
              : d.subjectivePoints != null
                ? String(d.subjectivePoints)
                : '',
          comment: teacherSaved ? d.review.comment ?? '' : d.aiOverall ?? d.review.comment ?? '',
          strengths: teacherSaved ? d.review.strengths ?? '' : d.aiStrength ?? d.review.strengths ?? '',
          suggestions: teacherSaved ? d.review.suggestions ?? '' : d.aiSuggestion ?? d.review.suggestions ?? '',
          reason: '',
        })
      })
      .catch((e: Error) => setDetailError(e))
  }, [selected])

  useEffect(() => { loadDetail() }, [loadDetail])

  const act = async (fn: () => Promise<unknown>, message?: string) => {
    setBusy(true)
    setActionError(null)
    try {
      await fn()
      loadQueue()
      loadDetail()
      // 提示必须在 loadDetail 之后写：它开头会把 actionError 清空，先写会被抹掉。
      if (message) setActionError(message)
    } catch (e) {
      setActionError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const editPayload = () => ({
    subjectivePoints: form.subjectivePoints === '' ? null : Number(form.subjectivePoints),
    comment: form.comment || null,
    strengths: form.strengths || null,
    suggestions: form.suggestions || null,
  })

  /** 只保存分数与三段评语，不让学生看到（复核状态变 EDITED）。 */
  const saveDraft = () => act(() => api.put(`/api/submissions/${selected}/review`, editPayload()),
    t('review.savedDraft'))

  /** T250：一个按钮走完「保存 + 发布」；这才是老师「提交这一份批改意见」的动作。 */
  const publishFeedback = async () => {
    if (!selected) return
    if (!form.subjectivePoints.trim()) {
      setActionError(t('review.needScore'))
      return
    }
    if (detail?.review.status === 'REJECTED') {
      setActionError(t('review.rejectedCannotPublish'))
      return
    }
    setBusy(true)
    setActionError(null)
    try {
      const status = detail?.review.status
      if (status === 'PENDING' || status === 'EDITED') {
        await api.put(`/api/submissions/${selected}/review`, editPayload())
      }
      await api.post(`/api/submissions/${selected}/review/publish`)
      loadQueue()
      loadDetail()
      setActionError(t('review.publishedOk'))
    } catch (e) {
      setActionError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const rejectReview = () => act(() => api.post(`/api/submissions/${selected}/review`, {
    status: 'REJECTED', reason: form.reason || null, comment: form.comment || null,
  }), t('review.rejectedOk'))

  const withdraw = () => act(() => api.post(`/api/submissions/${selected}/review/withdraw`),
    t('review.withdrawnOk'))

  const previewBulkApprove = async () => {
    if (!queue || queue.length === 0) return
    setBusy(true)
    setActionError(null)
    setBatch(null)
    try {
      setBatchPreview(await api.post<BulkApprovePreview>('/api/grading/bulk-approve/preview', {
        submissionIds: queue.map((r) => r.submissionId),
      }))
    } catch (e) {
      setActionError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const bulkApprove = async () => {
    if (!queue || queue.length === 0) return
    setBusy(true)
    setActionError(null)
    try {
      setBatch(await api.post<BulkApproveResult>('/api/grading/bulk-approve', {
        submissionIds: queue.map((r) => r.submissionId),
      }))
      setBatchPreview(null)
      loadQueue()
    } catch (e) {
      setActionError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const columns: Column<ReviewView>[] = [
    { key: 'submissionId', title: t('review.submissionId'), value: (r) => r.submissionId, sortable: true, render: (r) => <span className="mono small">{r.submissionId.slice(0, 8)}</span> },
    { key: 'status', title: t('review.status'), value: (r) => r.status, sortable: true, render: (r) => <span className="tag amber">{t(`review.status_${r.status}`)}</span> },
    { key: 'createdAt', title: t('review.queuedAt'), value: (r) => r.createdAt, sortable: true, render: (r) => <span className="small">{fmtDateTime(r.createdAt)}</span> },
    { key: 'actions', title: t('review.open'), value: () => '', render: (r) => <Button size="small" onClick={() => openSubmission(r.submissionId)}>{t('review.open')}</Button> },
  ]

  const reviewRows = detail ? reviewRowsOf(detail) : []
  const autoRows = reviewRows.filter((row) => isAutoGraded(row.item))
  const objectiveRows = reviewRows.filter((row) => row.item.questionType !== 'ESSAY')
  const essayRows = reviewRows.filter((row) => row.item.questionType === 'ESSAY')
  const correctCount = autoRows.filter((row) => row.tone === 'correct').length
  const partialCount = autoRows.filter((row) => row.tone === 'partial').length
  const wrongCount = autoRows.filter((row) => row.tone === 'wrong').length
  const earnedPoints = autoRows.reduce((sum, row) => sum + (row.awarded ?? 0), 0)
  const fullPoints = autoRows.reduce((sum, row) => sum + Number(row.item.points), 0)
  const hasContent = Boolean(detail?.contentHtml?.trim() || detail?.contentText?.trim())
  const aiGraded = Boolean(
    detail &&
      (detail.aiSubjectiveDraft != null ||
        (detail.aiCriteria ?? []).length > 0 ||
        detail.aiOverall ||
        detail.aiStrength ||
        detail.aiSuggestion),
  )
  const aiHasText = Boolean(detail?.aiOverall || detail?.aiStrength || detail?.aiSuggestion)
  const aiScoreHint =
    detail?.aiSubjectiveDraft != null
      ? aiHasText
        ? t('review.aiPrefillHint', { score: fmtNum(detail.aiSubjectiveDraft) })
        : t('review.aiPrefillScoreOnly', { score: fmtNum(detail.aiSubjectiveDraft) })
      : t('review.aiPrefillHintNoScore')

  return (
    <>
      <PageHeader title={t('review.title')} sub={t('review.sub')} />
      <div className="stats">
        <StatCard value={queue?.length ?? 0} label={t('review.pending')} />
      </div>
      <div className="row" style={{ marginBottom: 10 }}>
        <Button disabled={busy || !queue?.length} onClick={() => void previewBulkApprove()}>{t('review.bulk')}</Button>
        {batchPreview && (
          <span className="small muted">
            {t('review.batchPreview', {
              approvable: batchPreview.approvable,
              skipped: batchPreview.skipped,
            })}
            {batchPreview.approvable > 0 && (
              <Button size="small" disabled={busy} onClick={() => void bulkApprove()} style={{ marginLeft: 8 }}>
                {t('review.batchConfirm')}
              </Button>
            )}
          </span>
        )}
        {batch && <span className="small muted">{t('review.batchResult', { approved: batch.approved, skipped: batch.skipped, total: batch.total })}</span>}
      </div>
      {queueError && <ErrorState error={queueError} onRetry={loadQueue} />}
      {!queue && !queueError && <Loading />}
      {queue && queue.length === 0 && <Empty>{t('review.emptyQueue')}</Empty>}
      {queue && queue.length > 0 && (
        <DataTable rows={queue} columns={columns} rowKey={(r) => r.id} pageSize={10} empty={t('review.emptyQueue')}
          onRowClick={(r) => openSubmission(r.submissionId)} />
      )}

      {!selected ? <p className="muted">{t('review.noDetail')}</p> : (
        <>
          {detailError && <ErrorState error={detailError} onRetry={loadDetail} />}
          {!detail && !detailError && <Loading />}
          {detail && (
            <div className="card">
              <div className="row" style={{ justifyContent: 'space-between' }}>
                <h2 style={{ margin: 0 }}>
                  {t('review.detailTitle')} <span className="mono small">{detail.submissionId.slice(0, 8)}</span>
                  {detail.studentName ? <span className="small muted"> · {detail.studentName}</span> : null}
                </h2>
                <span className="tag gray">{fmtDateTime(detail.review.createdAt)}</span>
              </div>
              {actionError && <div className="inline-note">{actionError}</div>}

              <h3>{t('review.content')}</h3>
              <div className="card" style={{ background: 'var(--panel-2)' }}>
                {detail.contentHtml && detail.contentHtml.trim()
                  ? <div className="rich-content" dangerouslySetInnerHTML={{ __html: detail.contentHtml }} />
                  : <div style={{ whiteSpace: 'pre-wrap' }}>{detail.contentText || '—'}</div>}
                <div className="row" style={{ gap: 24, marginTop: 10 }}>
                  <div><span className="muted small">{t('review.objective')}</span> <b className="mono">{detail.objectivePoints ?? '—'}</b></div>
                  <div><span className="muted small">{t('review.subjective')}</span> <b className="mono">{detail.subjectivePoints ?? '—'}</b></div>
                  <div><span className="muted small">{t('review.aiDraft')}</span> <b className="mono">{detail.aiSubjectiveDraft ?? t('review.aiNotInvolved')}</b></div>
                </div>
                {(detail.objectivePoints == null || detail.subjectivePoints == null) && <div className="hint">{t('review.notConnectedScore')}</div>}
              </div>

              <h3>{t('review.aiReview')}</h3>
              {detail.aiFeedback?.includes('stub engine') && (
                <div className="inline-note danger" style={{ marginBottom: 8 }}>
                  {t('review.stubNotice')}
                </div>
              )}
              {detail.aiFailure && (
                <div className="inline-note danger" style={{ marginBottom: 8 }}>
                  {t('review.aiFailure', {
                    reason: detail.aiFailure === 'NO_KEY' ? t('review.aiFailureNoKey')
                      : detail.aiFailure === 'AUTH_FAILED' ? t('review.aiFailureAuth')
                        : detail.aiFailure === 'TIMEOUT' ? t('review.aiFailureTimeout')
                          : detail.aiFailure === 'INVALID_OUTPUT' ? t('review.aiFailureInvalid')
                            : detail.aiFailure,
                  })}
                </div>
              )}
              {detail.paragraphBoundarySensitive && (
                <div className="inline-note" style={{ marginBottom: 8 }}>
                  {t('review.boundarySensitive', { count: detail.paragraphCount ?? '—' })}
                </div>
              )}
              {detail.aiSubjectiveDraft == null && (detail.aiCriteria ?? []).length === 0 ? (
                <div className="inline-note">{t('review.aiNotInvolved')}</div>
              ) : (
                <div className="card" style={{ background: 'var(--panel-2)' }}>
                  <div className="row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
                    <div>
                      <span className="muted small">{t('review.aiSuggested')}</span>{' '}
                      <b className="mono" style={{ fontSize: 18 }}>{detail.aiSubjectiveDraft ?? '—'}</b>
                    </div>
                  </div>
                  {(detail.aiCriteria ?? []).length > 0 && (
                    <ul className="ai-criteria">
                      {(detail.aiCriteria ?? []).map((criterion) => (
                        <li key={criterion.name}>
                          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
                            <b>{criterion.name}</b>
                            <span className="mono small">{criterion.points} / {criterion.maxPoints}</span>
                          </div>
                          {criterion.comment && <div className="small muted">{criterion.comment}</div>}
                        </li>
                      ))}
                    </ul>
                  )}
                  {detail.aiOverall && (
                    <p className="small" style={{ whiteSpace: 'pre-wrap', marginBottom: 6 }}>
                      <b>{t('review.aiOverall')}</b> {detail.aiOverall}
                    </p>
                  )}
                  {detail.aiStrength && (
                    <p className="small" style={{ whiteSpace: 'pre-wrap', marginBottom: 6 }}>
                      <b>{t('review.aiStrengthLabel')}</b> {detail.aiStrength}
                    </p>
                  )}
                  {detail.aiSuggestion && (
                    <p className="small" style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                      <b>{t('review.aiSuggestionLabel')}</b> {detail.aiSuggestion}
                    </p>
                  )}
                  {!detail.aiOverall && detail.aiFeedback && (
                    <p className="small" style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{detail.aiFeedback}</p>
                  )}
                  {detail.aiPromptVersion && (
                    <p className="small muted" style={{ marginTop: 8, marginBottom: 0 }}>
                      {t('review.aiProvenance', {
                        version: detail.aiPromptVersion,
                        model: detail.aiModel ?? 'deepseek-flash',
                      })}
                    </p>
                  )}
                </div>
              )}

              <details className="review-more" style={{ marginTop: 14 }}>
                <summary>{t('review.itemsAndRubric')}</summary>
                <h3>{t('review.items')}</h3>
                {autoRows.length > 0 && (
                  <div className="row review-item-summary">
                    <span className="small muted">
                      {t('review.itemSummary', {
                        total: autoRows.length,
                        correct: correctCount,
                        wrong: wrongCount,
                        earned: fmtNum(earnedPoints),
                        full: fmtNum(fullPoints),
                      })}
                    </span>
                    {partialCount > 0 && (
                      <span className="tag amber">{t('review.partialCount', { count: partialCount })}</span>
                    )}
                  </div>
                )}
                <table className="review-items">
                  <thead>
                    <tr>
                      <th scope="col">#</th>
                      <th scope="col">{t('review.result')}</th>
                      <th scope="col">{t('review.answer')}</th>
                      <th scope="col">{t('review.answerKey')}</th>
                      <th scope="col" className="num">{t('review.earned')}</th>
                      <th scope="col" className="num">{t('review.fullPoints')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {reviewRows.map(({ item, answer, tone, awarded }) => {
                      const given = displayAnswer(answer)
                      return (
                        <tr key={item.number} className={`is-${tone}`}>
                          <td className="mono" title={item.stem ?? undefined}>{item.number}</td>
                          <td>
                            <span className={`tag ${toneTagClass(tone)}`}>
                              {t(`review.result_${tone}`)}
                            </span>
                          </td>
                          <td
                            className={`small mono answer-given is-${tone}`}
                            title={given === '—' ? undefined : given}
                          >
                            {given}
                          </td>
                          <td className="small mono">{item.answerKey ?? '—'}</td>
                          <td className={`mono num awarded is-${tone}`}>
                            {awarded == null ? '—' : fmtNum(awarded)}
                          </td>
                          <td className="mono num muted">{fmtNum(item.points)}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
                {detail.rubricMarkdown && (
                  <details style={{ marginTop: 10 }}>
                    <summary>{t('review.rubric')}</summary>
                    <pre className="small" style={{ whiteSpace: 'pre-wrap' }}>{detail.rubricMarkdown}</pre>
                  </details>
                )}
              </details>

              {detail.aiSubjectiveDraft == null && (
                <div className="inline-note" style={{ marginTop: 10 }}>{t('review.manualGradingHint')}</div>
              )}

              <h3>{t('review.teacherGrading')}</h3>
              {aiGraded && <div className="inline-note ok">{aiScoreHint}</div>}
              {(essayRows.length > 0 || objectiveRows.length > 0) && (
                <div className="grading-answer">
                  {essayRows.length > 0 && (
                    <div className="grading-answer-block">
                      <div className="small muted">{t('review.essayAnswers')}</div>
                      {essayRows.map(({ item, answer }) => {
                        const text = answer?.answer?.trim()
                        const stemText = item.stem ?? ''
                        return (
                          <div className="essay-answer" key={item.number}>
                            <div className="small muted">
                              {t('review.question', { number: item.number })}
                            </div>
                            {stemText.trim() ? <Markdown className="md-body" text={stemText} /> : null}
                            <div className="grading-answer-text small">
                              {text || (hasContent ? t('review.seeContentAbove') : t('review.unanswered'))}
                            </div>
                          </div>
                        )
                      })}
                    </div>
                  )}
                  {objectiveRows.length > 0 && (
                    <div className="grading-answer-block">
                      <div className="row" style={{ justifyContent: 'space-between' }}>
                        <span className="small muted">{t('review.answerSheet')}</span>
                        <span className="small muted">
                          {t('review.answerSheetSummary', { correct: correctCount, wrong: wrongCount })}
                        </span>
                      </div>
                      <div className="answer-sheet">
                        {objectiveRows.map(({ item, answer, tone }) => (
                          <span
                            key={item.number}
                            className={`answer-chip is-${tone}`}
                            title={[t('review.question', { number: item.number }), item.stem]
                              .filter(Boolean)
                              .join(' · ')}
                          >
                            <b>{item.number}</b>
                            <span>{displayAnswer(answer)}</span>
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}
              {!hasContent && reviewRows.length === 0 && (
                <p className="small muted" style={{ marginBottom: 0 }}>
                  {t('review.noStudentAnswer')}
                </p>
              )}
              <div className="row" style={{ alignItems: 'flex-start' }}>
                <label className="f" style={{ width: 120 }}><b>{t('review.subjective')} <span className="required-mark">*</span></b>
                  <input type="number" step="0.5" min={0} value={form.subjectivePoints}
                    onChange={(e) => setForm({ ...form, subjectivePoints: e.target.value })} />
                  {detail.aiSubjectiveDraft != null && (
                    <span className="hint">
                      {t('review.aiSuggestedScore', { score: fmtNum(detail.aiSubjectiveDraft) })}
                    </span>
                  )}
                </label>
                <label className="f" style={{ flex: 1, minWidth: 220 }}><b>{t('review.comment')}</b>
                  <textarea rows={3} value={form.comment} onChange={(e) => setForm({ ...form, comment: e.target.value })} />
                </label>
                <label className="f" style={{ flex: 1, minWidth: 220 }}><b>{t('review.strengths')}</b>
                  <textarea rows={3} value={form.strengths} onChange={(e) => setForm({ ...form, strengths: e.target.value })} />
                </label>
                <label className="f" style={{ flex: 1, minWidth: 220 }}><b>{t('review.suggestions')}</b>
                  <textarea rows={3} value={form.suggestions} onChange={(e) => setForm({ ...form, suggestions: e.target.value })} />
                </label>
              </div>

              <div className="row" style={{ justifyContent: 'space-between', alignItems: 'baseline', marginTop: 18 }}>
                <h3 style={{ margin: 0 }}>{t('review.actions')}</h3>
                <span className={`tag ${detail.review.published ? 'green' : 'gray'}`}>
                  {detail.review.published ? t('review.published') : t('review.unpublished')}
                </span>
              </div>
              <p className="small muted" style={{ margin: '4px 0 8px' }}>
                {t(`review.status_${detail.review.status}`)}
                {detail.review.publishedAt ? ` · ${fmtDateTime(detail.review.publishedAt)}` : ''}
              </p>

              <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                {!detail.review.published && (
                  <>
                    <Button variant="primary"
                      disabled={busy || detail.review.status === 'REJECTED' || !form.subjectivePoints.trim()}
                      onClick={() => void publishFeedback()}>{t('review.publishFeedback')}</Button>
                    <Button disabled={busy || detail.review.status === 'REJECTED'}
                      onClick={() => void saveDraft()}>{t('review.saveDraft')}</Button>
                  </>
                )}
                {detail.review.published && (
                  <Button disabled={busy} onClick={() => void withdraw()}>{t('review.withdraw')}</Button>
                )}
                <div style={{ flex: 1 }} />
                <Button onClick={() => setAuditFor(detail.submissionId)}>{t('review.audit')}</Button>
              </div>
              <p className="small muted" style={{ margin: '6px 0 0' }}>
                {detail.review.published ? t('review.publishedHint') : t('review.publishHint')}
              </p>
              {!form.subjectivePoints.trim() && !detail.review.published && (
                <p className="field-error" style={{ marginTop: 2 }}>{t('review.needScore')}</p>
              )}

              {!detail.review.published && (
                <div style={{ borderTop: '1px solid var(--line-2)', marginTop: 14, paddingTop: 12 }}>
                  <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                    <input style={{ flex: 1, minWidth: 220 }} placeholder={t('review.rejectReason')} value={form.reason}
                      onChange={(e) => setForm({ ...form, reason: e.target.value })}
                      disabled={detail.review.status === 'REJECTED'} />
                    <Button variant="danger" disabled={busy || detail.review.status === 'REJECTED'}
                      onClick={() => void rejectReview()}>{t('review.reject')}</Button>
                  </div>
                  <p className="small muted" style={{ margin: '6px 0 0' }}>{t('review.rejectHint')}</p>
                </div>
              )}
            </div>
          )}
        </>
      )}

      <AuditDrawer open={auditFor !== null} onClose={() => setAuditFor(null)}
        targetType="submission" targetId={auditFor ?? ''} title={t('review.audit')} />
    </>
  )
}
