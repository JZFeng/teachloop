import { useTranslation } from 'react-i18next'
import type { AttemptView, SubmitResponse } from '../api/types'
import { InlineNote } from '../ui'
import { fmtScore } from './examUtils'

interface ExamResultProps {
  title: string
  attempt: AttemptView | null
  result: SubmitResponse | null
  totalPoints: number
  autoSubmitted: boolean
  onHome: () => void
  onFeedback: () => void
}

/** 交卷后的客观得分（T187 / AC-21.8）。主观题待批改，错题清单后端未返回。 */
export function ExamResult({
  title, attempt, result, totalPoints, autoSubmitted, onHome, onFeedback,
}: ExamResultProps) {
  const { t } = useTranslation()

  const objective = attempt?.objectiveScore ?? result?.objectiveScore ?? null
  const subjective = attempt?.subjectiveScore ?? null
  const total = attempt?.totalScore ?? objective
  const gradingState = attempt?.gradingState ?? result?.gradingState ?? ''
  const pending = gradingState !== 'DONE' && gradingState !== 'SKIPPED'
  const wasAuto = attempt?.autoSubmitted ?? result?.autoSubmitted ?? autoSubmitted
  const lateBy = attempt?.lateBySeconds ?? result?.lateBySeconds ?? 0

  return (
    <div className="exam-focus-page">
      <div className="exam-result">
        <h1>{t('exam.resultTitle')}</h1>
        <div className="sub">{title}</div>

        {wasAuto && <div className="exam-banner danger">{t('exam.resultAuto')}</div>}

        <div className="card">
          <h2>{t('exam.resultScoreTitle')}</h2>
          <div className="exam-result-score">
            <div className="score-big">
              {total == null ? (
                <span className="small muted">{t('exam.scorePending')}</span>
              ) : (
                <>
                  {fmtScore(total)}
                  <small> / {fmtScore(totalPoints)}</small>
                </>
              )}
            </div>
            <div className="result-facts">
              <div className="kv"><span>{t('exam.resultObjective')}</span><span className="mono">{fmtScore(objective)}</span></div>
              <div className="kv"><span>{t('exam.resultSubjective')}</span><span className="mono">{fmtScore(subjective)}</span></div>
              <div className="kv">
                <span>{t('exam.resultAttempt')}</span>
                <span>{attempt ? attempt.attemptNo : result?.attemptNo ?? '—'}</span>
              </div>
            </div>
          </div>
          <div className="row" style={{ marginTop: 14 }}>
            {total == null
              ? <span className="tag amber">{t('exam.scorePending')}</span>
              : pending
                ? <span className="tag amber">{t('exam.resultPending')}</span>
                : <span className="tag green">{t('exam.resultGraded')}</span>}
            {lateBy > 0 && <span className="tag amber">{t('exam.resultLate', { minutes: Math.ceil(lateBy / 60) })}</span>}
          </div>
        </div>

        <div className="card">
          <h2>{t('exam.resultWrongTitle')}</h2>
          <InlineNote tone="warning">{t('exam.resultWrongMissing')}</InlineNote>
        </div>

        <div className="result-actions">
          <button type="button" className="btn pri" onClick={onFeedback}>{t('exam.viewFeedback')}</button>
          <button type="button" className="btn" onClick={onHome}>{t('exam.backHome')}</button>
        </div>
      </div>
    </div>
  )
}
