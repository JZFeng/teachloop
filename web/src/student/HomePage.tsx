import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { fmtDateTime } from '../api/client'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { fmtScore } from '../exam/examUtils'
import '../exam/exam.css'
import {
  latestAttempt,
  loadStudentRows,
  rowHasFeedback,
  rowIsExam,
  rowScore,
  rowStatus,
  sortRowsByDue,
  statusTagClass,
  type StudentRow,
} from './studentUtils'

/** T110/T193：学生首页列出自己的考试与作业，状态与分数都来自接口。 */
export default function StudentHomePage() {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const [rows, setRows] = useState<StudentRow[] | null>(null)
  const [error, setError] = useState<Error | null>(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      setRows(sortRowsByDue(await loadStudentRows()))
    } catch (loadError) {
      setError(loadError as Error)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const summary = useMemo(() => {
    const list = rows ?? []
    return {
      total: list.length,
      active: list.filter((row) => rowStatus(row) === 'in_progress').length,
      todo: list.filter((row) => rowStatus(row) === 'not_started').length,
      feedback: list.filter((row) => rowHasFeedback(row)).length,
    }
  }, [rows])

  if (error) return <ErrorState error={error} onRetry={() => void load()} />
  if (!rows) return <Loading label={t('student.loadingHome')} />

  return (
    <>
      <PageHeader
        title={t('student.homeTitle')}
        sub={t('student.homeSub')}
        actions={(
          <button type="button" className="btn" onClick={() => navigate('/feedback')}>
            {t('student.myFeedback')}
          </button>
        )}
      />

      {rows.length === 0 ? (
        <Empty>{t('student.homeEmpty')}</Empty>
      ) : (
        <>
          <div className="row small muted" style={{ marginBottom: 12 }}>
            <span>{t('student.homeSummary', summary)}</span>
          </div>
          <div className="feedgrid">
            {rows.map((row) => {
              const status = rowStatus(row)
              const score = rowScore(row)
              const attempt = latestAttempt(row)
              const exam = rowIsExam(row)
              const go = () => navigate(exam ? `/exams/${row.offering.id}` : `/assignments/${row.offering.id}`)
              return (
                <div
                  className="feed"
                  key={row.offering.id}
                  role="button"
                  tabIndex={0}
                  onClick={go}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault()
                      go()
                    }
                  }}
                >
                  <div className="top">
                    <div style={{ minWidth: 0 }}>
                      <b>{row.offering.paperTitle}</b>
                      <div className="meta">{t('student.dueAt')} {fmtDateTime(row.offering.dueAt)}</div>
                    </div>
                    <span className={`tag ${exam ? 'blue' : 'gray'}`}>
                      {exam ? t('student.exam') : t('student.assignment')}
                    </span>
                  </div>
                  <div className="row" style={{ marginTop: 8 }}>
                    <span className={`tag ${statusTagClass(status)}`}>{t(`student.status_${status}`)}</span>
                    {score !== null && (
                      <span className="small">
                        {t('student.score')} <b className="mono">{fmtScore(score)}</b>
                        {row.entry && <span className="muted"> / {fmtScore(row.entry.totalPoints)}</span>}
                      </span>
                    )}
                    {attempt?.submittedAt && (
                      <span className="small muted">
                        {t('student.scoreBreakdown', {
                          objective: fmtScore(attempt.objectiveScore),
                          subjective: fmtScore(attempt.subjectiveScore),
                        })}
                      </span>
                    )}
                    {rowHasFeedback(row) && <span className="tag green">{t('student.hasFeedback')}</span>}
                    {row.attempts && row.attempts.attemptsUsed > 1 && (
                      <span className="small muted">{t('student.attemptNo', { no: row.attempts.attemptsUsed })}</span>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </>
      )}
    </>
  )
}
