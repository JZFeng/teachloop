import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError, fmtDateTime } from '../api/client'
import type {
  AnswerSaveResponse,
  AttemptView,
  EntryView,
  MyAttemptsView,
  OfferingView,
  SubmitResponse,
} from '../api/types'
import { ErrorState, Loading, PageHeader } from '../components/States'
import { countWrittenWords, fmtScore } from './examUtils'
import './exam.css'

const LOCAL_DRAFT_PREFIX = 'teachloop.assignment.'

function readLocalDraft(offeringId: string): string {
  try {
    return window.localStorage.getItem(`${LOCAL_DRAFT_PREFIX}${offeringId}`) ?? ''
  } catch {
    return ''
  }
}

function writeLocalDraft(offeringId: string, text: string): void {
  try {
    window.localStorage.setItem(`${LOCAL_DRAFT_PREFIX}${offeringId}`, text)
  } catch {
    // 本地存储不可用时静默跳过
  }
}

function clearLocalDraft(offeringId: string): void {
  try {
    window.localStorage.removeItem(`${LOCAL_DRAFT_PREFIX}${offeringId}`)
  } catch {
    // ignore
  }
}

/**
 * T190 / US-22：非试卷作业单页（说明 + 评分标准 + 提交区）。
 * 后端没有 rubric 接口，评分标准明确标「未接入」，不编造。
 */
export default function AssignmentPage() {
  const { offeringId = '' } = useParams<{ offeringId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()

  const [entry, setEntry] = useState<EntryView | null>(null)
  const [offering, setOffering] = useState<OfferingView | null>(null)
  const [attempts, setAttempts] = useState<MyAttemptsView | null>(null)
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [lastSaved, setLastSaved] = useState<string | null>(null)
  const [submitResult, setSubmitResult] = useState<SubmitResponse | null>(null)

  const load = useCallback(async () => {
    if (!offeringId) return
    setLoading(true)
    setError(null)
    try {
      const [loadedEntry, loadedAttempts, loadedOffering] = await Promise.all([
        api.get<EntryView>(`/api/offerings/${offeringId}/entry`),
        api.get<MyAttemptsView>(`/api/offerings/${offeringId}/my-submissions`),
        api.get<OfferingView>(`/api/offerings/${offeringId}`),
      ])
      setEntry(loadedEntry)
      setAttempts(loadedAttempts)
      setOffering(loadedOffering)
      const latest = latestOf(loadedAttempts)
      const hasDraft = !!latest && latest.active && !latest.submittedAt && !latest.rejectedAt
      setContent(hasDraft ? readLocalDraft(offeringId) : '')
    } catch (loadError) {
      setError(loadError as Error)
    } finally {
      setLoading(false)
    }
  }, [offeringId])

  useEffect(() => {
    void load()
  }, [load])

  const latest = useMemo(() => latestOf(attempts), [attempts])
  const hasDraft = !!latest && latest.active && !latest.submittedAt && !latest.rejectedAt
  const rejected = !!latest?.rejectedAt
  const submitted = !rejected && (entry?.submitted ?? false)
  const canStart = !!attempts?.canRetry && !hasDraft && !submitted
  const attemptsLeft = attempts ? Math.max(0, attempts.maxAttempts - attempts.attemptsUsed) : null

  // 草稿正文只存本机：后端没有「读取草稿正文」的接口
  useEffect(() => {
    if (!hasDraft) return
    writeLocalDraft(offeringId, content)
  }, [content, hasDraft, offeringId])

  const startAttempt = async () => {
    setBusy(true)
    setActionError(null)
    try {
      await api.post(`/api/offerings/${offeringId}/start`)
      await load()
    } catch (startFailure) {
      setActionError((startFailure as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const saveDraft = async () => {
    if (!hasDraft || busy) return
    setBusy(true)
    setActionError(null)
    try {
      const response = await api.put<AnswerSaveResponse>(`/api/offerings/${offeringId}/answers`, {
        contentText: content,
        answers: [],
      })
      setLastSaved(response.serverTime)
    } catch (saveFailure) {
      if (saveFailure instanceof ApiError && saveFailure.status === 409) {
        setActionError(saveFailure.message)
        await load()
      } else {
        setActionError((saveFailure as Error).message)
      }
    } finally {
      setBusy(false)
    }
  }

  const submit = async () => {
    if (busy) return
    setBusy(true)
    setActionError(null)
    try {
      const response = await api.post<SubmitResponse>(`/api/offerings/${offeringId}/submissions`, {
        contentText: content,
        answers: [],
      })
      setSubmitResult(response)
      clearLocalDraft(offeringId)
      await load()
    } catch (submitFailure) {
      if (submitFailure instanceof ApiError && submitFailure.status === 409) {
        setActionError(submitFailure.message)
        await load()
      } else {
        setActionError((submitFailure as Error).message)
      }
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <Loading label={t('exam.loadingAssignment')} />
  if (error) return <ErrorState error={error} onRetry={() => void load()} />
  if (!entry) return null

  const words = countWrittenWords(content)
  const pastDue = Date.parse(entry.dueAt) < Date.now()
  const showResult = submitResult ?? null

  return (
    <>
      <PageHeader
        title={entry.paperTitle || t('exam.assignmentTitle')}
        sub={`${t('exam.assignmentSub')} · ${t('exam.dueAtLabel')} ${fmtDateTime(entry.dueAt)}`}
        actions={(
          <button type="button" className="btn" onClick={() => navigate('/')}>{t('exam.backHome')}</button>
        )}
      />

      <div className="card">
        <h2>{t('exam.assignmentNotes')}</h2>
        {entry.notice && <div className="inline-note">{entry.notice}</div>}
        <p className="small muted">
          {t('exam.assignmentNoCountdown')}
        </p>
        {entry.notes.length > 0 && (
          <ul className="small muted" style={{ paddingLeft: 20, lineHeight: 1.9, margin: '8px 0 0' }}>
            {entry.notes.map((note) => <li key={note}>{note}</li>)}
          </ul>
        )}
        <div className="assign-meta" style={{ marginTop: 12 }}>
          <span className="tag gray">{t('exam.assignmentKind')}</span>
          <span className="small muted">{t('exam.dueAtLabel')} {fmtDateTime(entry.dueAt)}</span>
          {offering && (
            <span className="small muted">
              {t('exam.graceLabel', { hours: offering.graceHours })}
            </span>
          )}
          {attemptsLeft !== null && (
            <span className="tag blue">
              {t('exam.attemptsLeft', { left: attemptsLeft, max: attempts?.maxAttempts ?? 0 })}
            </span>
          )}
          {pastDue && <span className="tag amber">{t('exam.lateHint')}</span>}
        </div>
      </div>

      <div className="card">
        <h2>{t('exam.assignmentRubric')}</h2>
        <div className="inline-note">{t('exam.rubricMissing')}</div>
      </div>

      {rejected && latest && (
        <div className="card">
          <h2>{t('exam.rejectedTitle')}</h2>
          <div className="exam-banner danger">
            {t('exam.rejectedReason', { reason: latest.rejectReason || t('exam.rejectedNoReason') })}
          </div>
          {canStart && (
            <button type="button" className="btn pri" disabled={busy} onClick={() => void startAttempt()}>
              {busy ? t('exam.working') : t('exam.resubmit')}
            </button>
          )}
          {!canStart && <p className="small muted">{t('exam.noAttemptsLeft')}</p>}
        </div>
      )}

      {showResult && (
        <div className="card">
          <h2>{t('exam.assignmentSubmitted')}</h2>
          <div className="row" style={{ alignItems: 'flex-end', gap: 24 }}>
            <div className="score-big">
              {fmtScore(showResult.objectiveScore)}
              <small> / {fmtScore(showResult.totalPoints)}</small>
            </div>
            <div className="small muted">
              <div>{t('exam.resultObjective')}: {fmtScore(showResult.objectiveScore)}</div>
              <div>{t('exam.resultSubjective')}: {t('exam.pendingGrade')}</div>
            </div>
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            <span className="tag amber">{t('exam.pendingGrade')}</span>
            {showResult.lateBySeconds > 0 && <span className="tag amber">{t('exam.lateTag')}</span>}
            <button type="button" className="btn" onClick={() => navigate(`/feedback/${offeringId}`)}>
              {t('exam.viewFeedback')}
            </button>
          </div>
        </div>
      )}

      {!rejected && submitted && !showResult && latest && (
        <div className="card">
          <h2>{t('exam.assignmentSubmitted')}</h2>
          <div className="row" style={{ alignItems: 'flex-end', gap: 24 }}>
            <div className="score-big">
              {fmtScore(latest.totalScore)}
              <small> / {fmtScore(entry.totalPoints)}</small>
            </div>
            <div className="small muted">
              <div>{t('exam.resultObjective')}: {fmtScore(latest.objectiveScore)}</div>
              <div>{t('exam.resultSubjective')}: {fmtScore(latest.subjectiveScore)}</div>
              <div>{t('exam.submittedAtLabel')}: {fmtDateTime(latest.submittedAt)}</div>
            </div>
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            {latest.gradingState === 'DONE'
              ? <span className="tag green">{t('exam.resultGraded')}</span>
              : <span className="tag amber">{t('exam.pendingGrade')}</span>}
            {latest.lateBySeconds > 0 && <span className="tag amber">{t('exam.lateTag')}</span>}
            {latest.autoSubmitted && <span className="tag gray">{t('exam.submittedAuto')}</span>}
            <button type="button" className="btn" onClick={() => navigate(`/feedback/${offeringId}`)}>
              {t('exam.viewFeedback')}
            </button>
          </div>
        </div>
      )}

      {hasDraft && !rejected && (
        <div className="card">
          <h2>{t('exam.assignmentSubmission')}</h2>
          <p className="small muted" style={{ marginTop: 0 }}>{t('exam.assignmentFormsMissing')}</p>
          <textarea
            className="writearea assignment-textarea"
            value={content}
            disabled={busy}
            placeholder={t('exam.assignmentPlaceholder')}
            onChange={(event) => setContent(event.target.value)}
          />
          <div className="wcount"><span className="wn">{words}</span> {t('exam.assignmentWordCount')}</div>
          <div className="row" style={{ marginTop: 12 }}>
            <button type="button" className="btn" disabled={busy} onClick={() => void saveDraft()}>
              {busy ? t('exam.saving') : t('exam.saveDraft')}
            </button>
            <button type="button" className="btn pri" disabled={busy} onClick={() => void submit()}>
              {busy ? t('exam.submitting') : t('exam.submitAssignment')}
            </button>
            <div style={{ flex: 1 }} />
            <span className="small muted">
              {lastSaved ? t('exam.savedAt', { time: fmtDateTime(lastSaved) }) : t('exam.draftLocalOnly')}
            </span>
          </div>
        </div>
      )}

      {!hasDraft && !submitted && !rejected && (
        <div className="card">
          <h2>{t('exam.assignmentSubmission')}</h2>
          {canStart ? (
            <>
              <p className="small muted" style={{ marginTop: 0 }}>{t('exam.assignmentStartHint')}</p>
              <button type="button" className="btn pri" disabled={busy} onClick={() => void startAttempt()}>
                {busy ? t('exam.working') : t('exam.startAssignment')}
              </button>
            </>
          ) : (
            <p className="small muted" style={{ marginTop: 0 }}>{t('exam.noAttemptsLeft')}</p>
          )}
        </div>
      )}

      {actionError && <div className="exam-banner danger">{actionError}</div>}
    </>
  )
}

function latestOf(attempts: MyAttemptsView | null): AttemptView | null {
  const list = attempts?.attempts ?? []
  if (!list.length) return null
  return [...list].sort((a, b) => a.attemptNo - b.attemptNo)[list.length - 1]
}
