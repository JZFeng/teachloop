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
  rowRejected,
  rowScore,
  rowStatus,
  sortRowsByDue,
  statusTagClass,
  type StudentRow,
} from './studentUtils'

/**
 * T110/T193：学生首页列出自己的考试与作业，状态与分数都来自接口。
 *
 * 2026-10-26（US-72 / 决策 027）：页头只说一次——删 eyebrow `DASHBOARD`、删可见副标题、删「我的反馈」按钮。
 * 依据是屏幕上已经写着的事实：副标题那半句规则每张卡上都有（「待老师公布」/「已公布」），
 * 汇总的四个数就是下面 tag 的展开，而「看评语」这个唯一有用的动作原本在页头，离证据最远。
 * 现在：计数变成可点筛选 chip，动作落在已公布那一行里。
 */
const FILTERS = ['all', 'todo', 'submitted', 'published'] as const
type RowFilter = (typeof FILTERS)[number]

export default function StudentHomePage() {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const [rows, setRows] = useState<StudentRow[] | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [filter, setFilter] = useState<RowFilter>('all')

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

  const counts = useMemo<Record<RowFilter, number>>(() => {
    const list = rows ?? []
    return {
      all: list.length,
      todo: list.filter((row) => rowStatus(row) === 'todo').length,
      submitted: list.filter((row) => rowStatus(row) === 'submitted').length,
      published: list.filter((row) => rowStatus(row) === 'published').length,
    }
  }, [rows])

  const visible = useMemo(
    () => (filter === 'all' ? rows ?? [] : (rows ?? []).filter((row) => rowStatus(row) === filter)),
    [rows, filter],
  )

  if (error) return <ErrorState error={error} onRetry={() => void load()} />
  if (!rows) return <Loading label={t('student.loadingHome')} />

  return (
    <>
      <PageHeader title={t('student.homeTitle')} info={t('student.homeInfo')} />

      {rows.length === 0 ? (
        <Empty>{t('student.homeEmpty')}</Empty>
      ) : (
        <>
          <div className="chips" style={{ marginBottom: 12 }}>
            {FILTERS.map((key) => (
              <button
                key={key}
                type="button"
                className={`chip${filter === key ? ' on' : ''}`}
                aria-pressed={filter === key}
                onClick={() => setFilter(key)}
              >
                {key === 'all' ? t('student.filterAll') : t(`student.status_${key}`)}
                <span className="mono">{counts[key]}</span>
              </button>
            ))}
          </div>

          {visible.length === 0 ? (
            <p className="small muted">{t('student.filterEmpty')}</p>
          ) : (
            <div className="feedgrid">
              {visible.map((row) => {
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
                      {rowRejected(row) && (
                        <span className="tag red">{t('student.rejectedBadge')}</span>
                      )}
                      {score !== null && (
                        <span className="small">
                          {t('student.score')} <b className="mono">{fmtScore(score)}</b>
                          {row.entry && <span className="muted"> / {fmtScore(row.entry.totalPoints)}</span>}
                        </span>
                      )}
                      {attempt?.submittedAt && attempt.totalScore != null && (
                        <span className="small muted">
                          {t('student.scoreBreakdown', {
                            objective: fmtScore(attempt.objectiveScore),
                            subjective: fmtScore(attempt.subjectiveScore),
                          })}
                        </span>
                      )}
                      {attempt?.submittedAt && attempt.totalScore == null && (
                        <span className="small muted">{t('student.scorePending')}</span>
                      )}
                      {/* 「看评语」是这一行唯一的主操作：它原来是个不可点的 tag，而页头那个同名按钮离证据最远。 */}
                      {rowHasFeedback(row) && (
                        <button
                          type="button"
                          className="chip info"
                          onClick={(event) => {
                            event.stopPropagation()
                            navigate(`/feedback/${row.offering.id}`)
                          }}
                        >
                          {t('student.viewComments')}
                        </button>
                      )}
                      {row.attempts && row.attempts.attemptsUsed > 1 && (
                        <span className="small muted">{t('student.attemptNo', { no: row.attempts.attemptsUsed })}</span>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </>
      )}
    </>
  )
}
