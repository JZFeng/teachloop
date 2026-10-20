import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, fmtDateTime, fmtDuration } from '../api/client'
import type { AttemptView, EntryView, MyAttemptsView, SessionView } from '../api/types'
import { ErrorState, Loading, PageHeader } from '../components/States'
import './exam.css'

/**
 * T200 / AC-21.16：考前须知先于作答。
 * individual 才显示「开始答题」；fixed_window 只显示服务端的统一开考提示。
 */
export default function ExamEntryPage() {
  const { offeringId = '' } = useParams<{ offeringId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()

  const [entry, setEntry] = useState<EntryView | null>(null)
  const [attempts, setAttempts] = useState<MyAttemptsView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!offeringId) return
    setLoading(true)
    setError(null)
    try {
      const [loadedEntry, loadedAttempts] = await Promise.all([
        api.get<EntryView>(`/api/offerings/${offeringId}/entry`),
        api.get<MyAttemptsView>(`/api/offerings/${offeringId}/my-submissions`).catch(() => null),
      ])
      setEntry(loadedEntry)
      setAttempts(loadedAttempts)
    } catch (loadError) {
      setError(loadError as Error)
    } finally {
      setLoading(false)
    }
  }, [offeringId])

  useEffect(() => {
    void load()
  }, [load])

  const start = async () => {
    if (!entry || starting) return
    setStarting(true)
    setStartError(null)
    try {
      await api.post<SessionView>(`/api/offerings/${offeringId}/start`)
      navigate(`/exams/${offeringId}/live`)
    } catch (startFailure) {
      setStartError((startFailure as Error).message)
    } finally {
      setStarting(false)
    }
  }

  if (loading) return <Loading label={t('exam.loadingEntry')} />
  if (error) {
    return (
      <>
        <ErrorState error={error} onRetry={() => void load()} />
      </>
    )
  }
  if (!entry) return null

  const individual = entry.timingMode === 'INDIVIDUAL'
  const latest = latestAttemptOf(attempts)
  const rejected = !!latest?.rejectedAt && !entry.submitted
  const canEnter = entry.started && !entry.submitted && !rejected
  const lockedOnly = entry.locked && !entry.submitted

  return (
    <>
      <PageHeader
        title={t('exam.entryTitle')}
        sub={individual ? t('exam.entrySubIndividual') : t('exam.entrySubFixed')}
        actions={(
          <button type="button" className="btn" onClick={() => navigate('/')}>
            {t('exam.backHome')}
          </button>
        )}
      />

      <div className="card">
        <h2>
          {entry.paperTitle}
          <small style={{ marginLeft: 8, fontWeight: 400, color: 'var(--muted-2)', fontSize: 12 }}>
            {t('exam.examType')}
          </small>
        </h2>

        <table style={{ marginBottom: 16 }}>
          <tbody>
            <tr>
              <th style={{ width: 150 }}>{t('exam.duration')}</th>
              <td>
                {t('exam.durationValue', { minutes: entry.durationMinutes })}
                <span className="muted"> · {individual ? t('exam.timingIndividual') : t('exam.timingFixed')}</span>
              </td>
            </tr>
            <tr>
              <th>{t('exam.itemsPoints')}</th>
              <td>{t('exam.itemsPointsValue', { items: entry.itemCount, points: entry.totalPoints })}</td>
            </tr>
            <tr>
              <th>{t('exam.window')}</th>
              <td>
                {entry.startAt
                  ? t('exam.windowValue', { start: fmtDateTime(entry.startAt), due: fmtDateTime(entry.dueAt) })
                  : t('exam.windowNoStart', { due: fmtDateTime(entry.dueAt) })}
              </td>
            </tr>
            <tr>
              <th>{t('exam.onceStarted')}</th>
              <td>{t('exam.onceStartedValue', { minutes: entry.durationMinutes })}</td>
            </tr>
            <tr>
              <th>{t('exam.autoSubmit')}</th>
              <td>{t('exam.autoSubmitValue')}</td>
            </tr>
            <tr>
              <th>{t('exam.modeTitle')}</th>
              <td>{t('exam.modeValue')}</td>
            </tr>
          </tbody>
        </table>

        {entry.notice && <div className="inline-note">{entry.notice}</div>}

        {entry.notes.length > 0 && (
          <>
            <h3 style={{ fontSize: 13, margin: '16px 0 8px' }}>{t('exam.notesTitle')}</h3>
            <ul className="small muted" style={{ paddingLeft: 20, lineHeight: 1.9, margin: 0 }}>
              {entry.notes.map((note) => <li key={note}>{note}</li>)}
            </ul>
          </>
        )}
      </div>

      <div className="card">
        <h2>{t('exam.statusTitle')}</h2>

        {entry.submitted && (
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <div>
              <span className="tag green">{entry.autoSubmitted ? t('exam.submittedAuto') : t('exam.submitted')}</span>
              {entry.deadlineAt && (
                <div className="small muted" style={{ marginTop: 6 }}>
                  {t('exam.deadline')} {fmtDateTime(entry.deadlineAt)}
                </div>
              )}
            </div>
            <button type="button" className="btn pri" onClick={() => navigate(`/feedback/${offeringId}`)}>
              {t('exam.viewFeedback')}
            </button>
          </div>
        )}

        {!entry.submitted && canEnter && (
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <div>
              <span className="tag blue">{t('exam.inProgress')}</span>
              <span className="remaining" style={{ marginLeft: 8 }}>
                {t('exam.remaining', { time: fmtDuration(entry.remainingSeconds) })}
              </span>
              {entry.deadlineAt && (
                <div className="small muted" style={{ marginTop: 6 }}>
                  {t('exam.deadline')} {fmtDateTime(entry.deadlineAt)}
                </div>
              )}
            </div>
            <button type="button" className="btn pri" onClick={() => navigate(`/exams/${offeringId}/live`)}>
              {t('exam.resume')}
            </button>
          </div>
        )}

        {rejected && latest && (
          <>
            <div className="exam-banner danger" style={{ marginTop: 0 }}>
              {t('exam.rejectedReason', { reason: latest.rejectReason || t('exam.rejectedNoReason') })}
            </div>
            {attempts?.canRetry ? (
              <button type="button" className="btn pri" disabled={starting} onClick={() => void start()}>
                {starting ? t('exam.starting') : t('exam.retryStart')}
              </button>
            ) : (
              <p className="small muted">{t('exam.noAttemptsLeft')}</p>
            )}
          </>
        )}

        {!entry.submitted && !canEnter && !rejected && (
          <div>
            {entry.canStart && individual && (
              <>
                <p className="small muted" style={{ marginTop: 0 }}>{t('exam.startHint')}</p>
                <button type="button" className="btn pri" disabled={starting} onClick={() => void start()}>
                  {starting ? t('exam.starting') : t('exam.start')}
                </button>
              </>
            )}
            {!entry.canStart && individual && (
              <p className="small muted" style={{ marginTop: 0 }}>{t('exam.startNotAllowed')}</p>
            )}
            {!individual && (
              <div>
                <div className="inline-note">{entry.notice || t('exam.fixedWindowNotice')}</div>
                {lockedOnly && <p className="small muted">{t('exam.lockedNote')}</p>}
              </div>
            )}
            {lockedOnly && individual && <p className="small muted">{t('exam.lockedNote')}</p>}
          </div>
        )}

        {startError && <div className="exam-banner danger" style={{ marginTop: 12 }}>{startError}</div>}
      </div>
    </>
  )
}

function latestAttemptOf(attempts: MyAttemptsView | null): AttemptView | null {
  const list = attempts?.attempts ?? []
  if (!list.length) return null
  return [...list].sort((a, b) => a.attemptNo - b.attemptNo)[list.length - 1]
}
