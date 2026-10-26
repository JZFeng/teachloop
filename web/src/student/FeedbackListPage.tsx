import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { fmtDateTime } from '../api/client'
import type { AttemptView, OfferingKind } from '../api/types'
import { DataTable, useListState } from '../ui'
import type { Column } from '../ui'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { fmtScore } from '../exam/examUtils'
import {
  latestAttempt,
  loadStudentRows,
  rowIsExam,
  rowRejected,
  rowStatus,
  sortRowsByDue,
  statusTagClass,
  type StudentRow,
} from './studentUtils'

const VIEW_KEY = 'teachloop.feedbackView'
type ViewMode = 'card' | 'table'

interface FeedbackItem {
  row: StudentRow
  attempt: AttemptView
}

function readView(): ViewMode {
  try {
    return window.localStorage.getItem(VIEW_KEY) === 'table' ? 'table' : 'card'
  } catch {
    return 'card'
  }
}

/** T193 / AC-12.6：卡片 / 表格两种视图可切换，选择被记住；默认时间倒序 + 分页。 */
export default function FeedbackListPage() {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const [rows, setRows] = useState<StudentRow[] | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [view, setView] = useState<ViewMode>(readView)
  // T443 / AC-68.3：排序、页码、每页条数写进 URL，卡片与表格两个视图共用同一套状态
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(
    { key: 'submittedAt', dir: 'desc' },
    10,
  )

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

  useEffect(() => {
    try {
      window.localStorage.setItem(VIEW_KEY, view)
    } catch {
      // 本地存储不可用时只在本次会话里记住
    }
  }, [view])

  const items = useMemo<FeedbackItem[]>(() => {
    const list: FeedbackItem[] = []
    for (const row of rows ?? []) {
      const attempt = latestAttempt(row)
      if (attempt?.submittedAt) list.push({ row, attempt })
    }
    return list.sort((a, b) => Date.parse(b.attempt.submittedAt ?? '') - Date.parse(a.attempt.submittedAt ?? ''))
  }, [rows])

  // 卡片视图用同一套分页状态；表格视图把排序与分页交给 DataTable
  const cardPage = useMemo(() => {
    const totalPages = Math.max(1, Math.ceil(items.length / pageSize))
    const current = Math.min(Math.max(1, page), totalPages)
    return { current, totalPages, slice: items.slice((current - 1) * pageSize, current * pageSize) }
  }, [items, page])

  const columns: Column<FeedbackItem>[] = useMemo(() => [
    {
      key: 'title',
      title: t('student.colTitle'),
      value: (item) => item.row.offering.paperTitle,
      sortable: true,
    },
    {
      key: 'kind',
      title: t('student.colType'),
      value: (item) => item.row.offering.kind,
      render: (item) => (
        <span className={`tag ${rowIsExam(item.row) ? 'blue' : 'gray'}`}>
          {rowIsExam(item.row) ? t('student.exam') : t('student.assignment')}
        </span>
      ),
      sortable: true,
    },
    {
      key: 'submittedAt',
      title: t('student.colSubmittedAt'),
      value: (item) => item.attempt.submittedAt ?? '',
      render: (item) => fmtDateTime(item.attempt.submittedAt),
      sortable: true,
    },
    {
      key: 'score',
      title: t('student.colScore'),
      // 未公布成绩时没有分：排序按 -1 落到末尾，显示交给 render。
      value: (item) => item.attempt.totalScore ?? -1,
      render: (item) => (
        <span className="mono">
          {item.attempt.totalScore == null
            ? t('student.scorePending')
            : `${fmtScore(item.attempt.totalScore)} / ${fmtScore(item.row.entry?.totalPoints ?? 0)}`}
        </span>
      ),
      sortable: true,
    },
    {
      key: 'status',
      title: t('student.colStatus'),
      value: (item) => rowStatus(item.row),
      render: (item) => {
        const status = rowStatus(item.row)
        return (
          <>
            <span className={`tag ${statusTagClass(status)}`}>{t(`student.status_${status}`)}</span>
            {rowRejected(item.row) && <span className="tag red">{t('student.rejectedBadge')}</span>}
          </>
        )
      },
      sortable: false,
    },
  ], [t])

  if (error) return <ErrorState error={error} onRetry={() => void load()} />
  if (!rows) return <Loading label={t('student.loadingFeedback')} />

  const kindLabel = (kind: OfferingKind) => (kind === 'EXAM' ? t('student.exam') : t('student.assignment'))

  return (
    <>
      <PageHeader
        title={t('student.feedbackTitle')}
        sub={t('student.feedbackSub')}
        actions={(
          <div className="chips" style={{ margin: 0 }}>
            <button
              type="button"
              className={`chip${view === 'card' ? ' on' : ''}`}
              aria-pressed={view === 'card'}
              onClick={() => setView('card')}
            >
              {t('student.viewCard')}
            </button>
            <button
              type="button"
              className={`chip${view === 'table' ? ' on' : ''}`}
              aria-pressed={view === 'table'}
              onClick={() => setView('table')}
            >
              {t('student.viewTable')}
            </button>
          </div>
        )}
      />

      {items.length === 0 ? (
        <Empty>{t('student.feedbackEmpty')}</Empty>
      ) : view === 'card' ? (
        <>
          <div className="feedgrid">
            {cardPage.slice.map(({ row, attempt }) => {
              const status = rowStatus(row)
              return (
                <div
                  className="feed"
                  key={row.offering.id}
                  role="button"
                  tabIndex={0}
                  onClick={() => navigate(`/feedback/${row.offering.id}`)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault()
                      navigate(`/feedback/${row.offering.id}`)
                    }
                  }}
                >
                  <div className="top">
                    <div style={{ minWidth: 0 }}>
                      <b>{row.offering.paperTitle}</b>
                      <div className="meta">
                        {fmtDateTime(attempt.submittedAt)} · {t('student.attemptNo', { no: attempt.attemptNo })}
                      </div>
                    </div>
                    <div className="right">
                      <div className="score-big" style={{ fontSize: 22 }}>
                        {attempt.totalScore == null ? (
                          <span className="tag gray">{t('student.scorePending')}</span>
                        ) : (
                          <>
                            {fmtScore(attempt.totalScore)}
                            <small className="muted"> / {fmtScore(row.entry?.totalPoints ?? 0)}</small>
                          </>
                        )}
                      </div>
                    </div>
                  </div>
                  <div className="row">
                    <span className={`tag ${rowIsExam(row) ? 'blue' : 'gray'}`}>{kindLabel(row.offering.kind)}</span>
                    <span className={`tag ${statusTagClass(status)}`}>{t(`student.status_${status}`)}</span>
                    {rowRejected(row) && <span className="tag red">{t('student.rejectedBadge')}</span>}
                    {attempt.totalScore != null && (
                      <span className="tag gray">
                        {t('student.scoreBreakdown', {
                          objective: fmtScore(attempt.objectiveScore),
                          subjective: fmtScore(attempt.subjectiveScore),
                        })}
                      </span>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
          <div className="row" style={{ justifyContent: 'space-between', marginTop: 14 }}>
            <span className="small muted">
              {t('student.pagerSummary', { total: items.length, page: cardPage.current, pages: cardPage.totalPages })}
            </span>
            <div className="row">
              <button type="button" className="btn" disabled={cardPage.current <= 1} onClick={() => setPage(cardPage.current - 1)}>
                {t('student.prevPage')}
              </button>
              <button type="button" className="btn" disabled={cardPage.current >= cardPage.totalPages} onClick={() => setPage(cardPage.current + 1)}>
                {t('student.nextPage')}
              </button>
            </div>
          </div>
        </>
      ) : (
        <DataTable
          rows={items}
          columns={columns}
          rowKey={(item) => `${item.row.offering.id}-${item.attempt.attemptNo}`}
          sort={sort}
          page={page}
          pageSize={pageSize}
          onSortChange={setSort}
          onPageChange={setPage}
          onPageSizeChange={setPageSize}
          onRowClick={(item) => navigate(`/feedback/${item.row.offering.id}`)}
          empty={t('student.feedbackEmpty')}
        />
      )}
    </>
  )
}
