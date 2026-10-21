import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, fmtDateTime } from '../api/client'
import type { MyFeedback } from '../api/types'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { fmtScore } from '../exam/examUtils'

/**
 * T194 / AC-12.7：信息顺序固定为 ① 总分 ② 错题与解析 ③ 主观评语 ④ 全班概览。
 * 错题与标准答案来自 /my-feedback（发布后才返回）；逐题 AI 解析属于 T115，尚未生成。
 */
export default function FeedbackDetailPage() {
  const { offeringId = '' } = useParams<{ offeringId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()

  const [feedback, setFeedback] = useState<MyFeedback | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)

  const load = useCallback(async () => {
    if (!offeringId) return
    setLoading(true)
    setError(null)
    try {
      setFeedback(await api.get<MyFeedback>(`/api/offerings/${offeringId}/my-feedback`))
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
  if (!feedback) return null

  const attempt = feedback.latestAttempt

  return (
    <>
      <PageHeader
        title={feedback.paperTitle}
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

      {!attempt ? (
        <Empty>{t('student.noSubmission')}</Empty>
      ) : (
        <>
          {/* ① 我的总分 */}
          <div className="card">
            <h2>① {t('student.myScore')}</h2>
            <div className="exam-result-score">
              <div className="score-big">
                {fmtScore(attempt.totalScore)}
                <small> / {fmtScore(feedback.totalPoints)}</small>
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
              {!feedback.feedbackPublished && <span className="tag gray">{feedback.feedbackMessage}</span>}
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
            {!feedback.feedbackPublished ? (
              <div className="inline-note">{feedback.feedbackMessage}</div>
            ) : feedback.wrongItems.length === 0 ? (
              <div className="inline-note">{t('student.wrongNone')}</div>
            ) : (
              <>
                <ol style={{ paddingLeft: 20, marginTop: 0 }}>
                  {feedback.wrongItems.map((item) => (
                    <li key={item.number} style={{ marginBottom: 14 }}>
                      <div className="exam-stem" style={{ margin: '2px 0 6px' }}>
                        {t('student.questionMeta', { number: item.number, points: item.points })}{item.stem ? ` · ${item.stem}` : ''}
                      </div>
                      <div className="small">
                        <span className="muted">{t('student.wrongGiven')}：</span>
                        <span className="mono">{item.given || '—'}</span>
                        <span className="muted" style={{ marginLeft: 16 }}>{t('student.wrongCorrect')}：</span>
                        <span className="mono">{item.correctAnswer || '—'}</span>
                      </div>
                      {item.explanation ? (
                        <div className="small" style={{ marginTop: 4, whiteSpace: 'pre-wrap' }}>{item.explanation}</div>
                      ) : (
                        <div className="small muted" style={{ marginTop: 4 }}>{t('student.explanationMissing')}</div>
                      )}
                    </li>
                  ))}
                </ol>
                <p className="small muted" style={{ marginBottom: 0 }}>{t('student.explanationMissingHint')}</p>
              </>
            )}
          </div>

          {/* ③ 主观评语 */}
          <div className="card">
            <h2>③ {t('student.commentTitle')}</h2>
            {!feedback.feedbackPublished && <div className="inline-note">{feedback.feedbackMessage}</div>}
            {feedback.feedbackPublished && feedback.review?.comment && (
              <>
                <p style={{ marginTop: 0, whiteSpace: 'pre-wrap' }}>{feedback.review.comment}</p>
                {feedback.review.strengths && (
                  <p style={{ whiteSpace: 'pre-wrap' }}>
                    <b>{t('student.strengthsLabel')}</b>
                    {feedback.review.strengths}
                  </p>
                )}
                {feedback.review.suggestions && (
                  <p style={{ whiteSpace: 'pre-wrap' }}>
                    <b>{t('student.suggestionsLabel')}</b>
                    {feedback.review.suggestions}
                  </p>
                )}
                <div className="small muted">
                  {t('student.commentFrom', { date: fmtDateTime(feedback.review.createdAt) })}
                </div>
              </>
            )}
            {feedback.feedbackPublished && !feedback.review?.comment && (
              <div className="inline-note">{t('student.commentMissing')}</div>
            )}
          </div>

          {/* ④ 全班概览 */}
          <div className="card">
            <h2>④ {t('student.peerTitle')}</h2>
            <div className="row" style={{ gap: 28 }}>
              <div><div className="small muted">{t('student.peerExpected')}</div><b>{feedback.overview.expected}</b></div>
              <div><div className="small muted">{t('student.peerSubmitted')}</div><b>{feedback.overview.submitted}</b></div>
              <div>
                <div className="small muted">{t('student.peerAverage')}</div>
                <b>{feedback.overview.classAverage == null ? '—' : fmtScore(feedback.overview.classAverage)}</b>
              </div>
            </div>
            {!feedback.overview.peersVisible && (
              <div className="inline-note" style={{ marginTop: 12 }}>{t('student.peerClosed')}</div>
            )}
            <p className="small muted" style={{ marginBottom: 0 }}>{t('student.peerPrivacy')}</p>
          </div>
        </>
      )}
    </>
  )
}
