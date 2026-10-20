import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError, fmtDateTime } from '../api/client'
import type {
  AttemptView,
  EntryView,
  MyAttemptsView,
  OfferingView,
  ReviewView,
  SessionView,
} from '../api/types'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { fmtScore } from '../exam/examUtils'
import { latestOf } from './studentUtils'

type ReviewState = 'loading' | 'none' | 'ready' | 'error'

/**
 * T194 / AC-12.7：信息顺序固定为 ① 总分 ② 错题与解析 ③ 主观评语 ④ 全班概览。
 * 后端未提供错题/解析接口，明确标「未接入」，不编造答案与解析。
 */
export default function FeedbackDetailPage() {
  const { offeringId = '' } = useParams<{ offeringId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()

  const [entry, setEntry] = useState<EntryView | null>(null)
  const [offering, setOffering] = useState<OfferingView | null>(null)
  const [attempts, setAttempts] = useState<MyAttemptsView | null>(null)
  const [review, setReview] = useState<ReviewView | null>(null)
  const [reviewState, setReviewState] = useState<ReviewState>('loading')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)

  const load = useCallback(async () => {
    if (!offeringId) return
    setLoading(true)
    setError(null)
    setReviewState('loading')
    try {
      const [loadedEntry, loadedAttempts, loadedOffering, loadedSession] = await Promise.all([
        api.get<EntryView>(`/api/offerings/${offeringId}/entry`),
        api.get<MyAttemptsView>(`/api/offerings/${offeringId}/my-submissions`),
        api.get<OfferingView>(`/api/offerings/${offeringId}`),
        api.get<SessionView>(`/api/offerings/${offeringId}/session`).catch(() => null),
      ])
      setEntry(loadedEntry)
      setAttempts(loadedAttempts)
      setOffering(loadedOffering)

      const submissionId = loadedSession?.submissionId ?? null
      if (!submissionId) {
        setReviewState('none')
      } else {
        try {
          const loadedReview = await api.get<ReviewView>(`/api/submissions/${submissionId}/review`)
          setReview(loadedReview)
          setReviewState('ready')
        } catch (reviewFailure) {
          if (reviewFailure instanceof ApiError && reviewFailure.status === 404) setReviewState('none')
          else setReviewState('error')
        }
      }
    } catch (loadError) {
      setError(loadError as Error)
    } finally {
      setLoading(false)
    }
  }, [offeringId])

  useEffect(() => {
    void load()
  }, [load])

  if (loading) return <Loading label={t('student.loadingDetail')} />
  if (error) return <ErrorState error={error} onRetry={() => void load()} />
  if (!entry) return null

  const attempt: AttemptView | null = latestOf(attempts)
  const submitted = !!attempt?.submittedAt

  return (
    <>
      <PageHeader
        title={entry.paperTitle}
        sub={attempt
          ? `${t('student.attemptNo', { no: attempt.attemptNo })} · ${t('student.submittedAt')} ${fmtDateTime(attempt.submittedAt)}`
          : t('student.detailSub')}
        actions={(
          <div className="row">
            <button type="button" className="btn" onClick={() => navigate('/feedback')}>
              {t('student.backToList')}
            </button>
            <button type="button" className="btn" onClick={() => navigate('/')}>{t('student.backHome')}</button>
          </div>
        )}
      />

      {!attempt || !submitted ? (
        <Empty>{t('student.noSubmission')}</Empty>
      ) : (
        <>
          {/* ① 我的总分 */}
          <div className="card">
            <h2>① {t('student.myScore')}</h2>
            <div className="exam-result-score">
              <div className="score-big">
                {fmtScore(attempt.totalScore)}
                <small> / {fmtScore(entry.totalPoints)}</small>
              </div>
              <div className="result-facts">
                <div className="kv">
                  <span>{t('student.objective')}</span>
                  <span className="mono">{fmtScore(attempt.objectiveScore)}</span>
                </div>
                <div className="kv">
                  <span>{t('student.subjective')}</span>
                  <span className="mono">{fmtScore(attempt.subjectiveScore)}</span>
                </div>
                <div className="kv">
                  <span>{t('student.gradingState')}</span>
                  <span>{t(`student.gradingState_${attempt.gradingState}`)}</span>
                </div>
              </div>
            </div>
            <div className="row" style={{ marginTop: 12 }}>
              {attempt.gradingState === 'DONE'
                ? <span className="tag green">{t('student.status_graded')}</span>
                : <span className="tag amber">{t('student.pendingGrade')}</span>}
              {attempt.autoSubmitted && <span className="tag gray">{t('student.autoSubmitted')}</span>}
              {attempt.lateBySeconds > 0 && <span className="tag amber">{t('student.lateTag')}</span>}
              {attempt.rejectedAt && <span className="tag red">{t('student.status_rejected')}</span>}
            </div>
            {attempt.rejectedAt && (
              <div className="exam-banner danger" style={{ marginTop: 12 }}>
                {t('student.rejectedReason', { reason: attempt.rejectReason || t('student.rejectedNoReason') })}
              </div>
            )}
          </div>

          {/* ② 错题与解析 */}
          <div className="card">
            <h2>② {t('student.wrongTitle')}</h2>
            <div className="inline-note">{t('student.wrongMissing')}</div>
            <p className="small muted" style={{ marginBottom: 0 }}>{t('student.wrongMissingHint')}</p>
          </div>

          {/* ③ 主观评语 */}
          <div className="card">
            <h2>③ {t('student.commentTitle')}</h2>
            {reviewState === 'loading' && <span className="muted">{t('student.loadingComment')}</span>}
            {reviewState !== 'loading' && review?.comment && (
              <>
                <p style={{ marginTop: 0, whiteSpace: 'pre-wrap' }}>{review.comment}</p>
                <div className="small muted">
                  {t('student.commentFrom', { date: fmtDateTime(review.createdAt) })}
                </div>
              </>
            )}
            {reviewState !== 'loading' && !review?.comment && (
              <div className="inline-note">
                {reviewState === 'error' ? t('student.commentError') : t('student.commentMissing')}
              </div>
            )}
          </div>

          {/* ④ 全班概览 */}
          <div className="card">
            <h2>④ {t('student.peerTitle')}</h2>
            {offering?.showPeerOverview === false ? (
              <div className="inline-note">{t('student.peerClosed')}</div>
            ) : (
              <div className="inline-note">{t('student.peerMissing')}</div>
            )}
            <p className="small muted" style={{ marginBottom: 0 }}>{t('student.peerPrivacy')}</p>
          </div>
        </>
      )}
    </>
  )
}
