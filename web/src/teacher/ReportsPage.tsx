import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import type { ClassView, OfferingView, ReportWrongItem, WrongItemsReport } from '../api/types'
import { DataTable, useListState } from '../ui'
import type { Column } from '../ui'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import {
  fmtNum,
  fmtRate,
  loadAggregates,
  Tag,
  TeacherPage,
  useApiData,
} from './common'
import type { OfferingAggregate } from './common'

interface ReportsData {
  offerings: OfferingView[]
  classes: ClassView[]
  aggregates: Record<string, OfferingAggregate>
}

interface ReportRow {
  key: string
  offeringId: string
  offeringTitle: string
  dueAt: string
  classId: string
  className: string
  expected: number
  submitted: number
  onTime: number
  late: number
  rejected: number
  onTimeRate: number | null
  averageScore: number | null
}

/**
 * T192 报表：先用已接入的 /class-summary 做「按作业 × 班级」汇总。
 * Top N 错题、跨班跨时间段聚合、正确率分布、导出均无后端接口，
 * 页面上明确标注「未接入」，不编造任何数字。
 */
export default function ReportsPage() {
  const { t } = useTranslation('translation')
  const [params, setParams] = useSearchParams()
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(
    { key: 'dueAt', dir: 'desc' },
    10,
  )

  const { data, loading, error, reload } = useApiData<ReportsData>(async () => {
    const [offerings, classes] = await Promise.all([
      api.get<OfferingView[]>('/api/offerings'),
      api.get<ClassView[]>('/api/classes'),
    ])
    return { offerings, classes, aggregates: await loadAggregates(offerings) }
  }, [])

  const [wrongReport, setWrongReport] = useState<WrongItemsReport | null>(null)
  const [wrongLoading, setWrongLoading] = useState(false)
  const [wrongError, setWrongError] = useState<Error | null>(null)
  const [wrongLimit, setWrongLimit] = useState(10)
  const [wrongPage, setWrongPage] = useState(1)
  const [wrongPageSize, setWrongPageSize] = useState(10)

  const offerings = data?.offerings ?? []
  const classes = data?.classes ?? []
  const aggregates = data?.aggregates ?? {}
  const chosenOffering = params.get('offering') ?? 'all'
  const chosenClasses = new Set((params.get('classes') ?? '').split(',').filter(Boolean))

  const rows: ReportRow[] = offerings.flatMap((offering) => {
    const agg = aggregates[offering.id]
    if (!agg) return []
    return agg.summary.map((summary) => ({
      key: `${offering.id}-${summary.classId}`,
      offeringId: offering.id,
      offeringTitle: offering.paperTitle,
      dueAt: offering.dueAt,
      classId: summary.classId,
      className: classes.find((c) => c.id === summary.classId)?.name ?? summary.classId,
      expected: Number(summary.expected),
      submitted: Number(summary.submitted),
      onTime: Number(summary.onTime),
      late: Number(summary.late),
      rejected: Number(summary.rejected),
      onTimeRate: summary.onTimeRate === null ? null : Number(summary.onTimeRate),
      averageScore: summary.averageScore === null ? null : Number(summary.averageScore),
    }))
  })

  const filtered = rows.filter((row) => {
    if (chosenOffering !== 'all' && row.offeringId !== chosenOffering) return false
    if (chosenClasses.size > 0 && !chosenClasses.has(row.classId)) return false
    return true
  })

  const setOffering = (value: string) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      if (value === 'all') next.delete('offering')
      else next.set('offering', value)
      next.delete('page')
      return next
    })
  }

  const toggleClass = (classId: string) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      const chosen = new Set((next.get('classes') ?? '').split(',').filter(Boolean))
      if (chosen.has(classId)) chosen.delete(classId)
      else chosen.add(classId)
      if (chosen.size > 0) next.set('classes', [...chosen].join(','))
      else next.delete('classes')
      next.delete('page')
      return next
    })
  }

  const queryWrongItems = async () => {
    const offeringIds = chosenOffering === 'all' ? offerings.map((o) => o.id) : [chosenOffering]
    if (offeringIds.length === 0) return
    setWrongLoading(true)
    setWrongError(null)
    try {
      const query = new URLSearchParams({ offeringIds: offeringIds.join(','), limit: String(wrongLimit) })
      if (chosenClasses.size > 0) query.set('classIds', [...chosenClasses].join(','))
      setWrongReport(await api.get<WrongItemsReport>(`/api/reports/wrong-items?${query.toString()}`))
      setWrongPage(1)
    } catch (e) {
      setWrongError(e as Error)
    } finally {
      setWrongLoading(false)
    }
  }

  const wrongColumns: Column<ReportWrongItem>[] = [
    { key: 'number', title: t('teacher.reports.wrongNumber'), value: (r) => r.number, sortable: true, render: (r) => <span className="mono">{r.number}</span> },
    { key: 'stem', title: t('teacher.reports.wrongStem'), value: (r) => r.stem ?? '', sortable: true, render: (r) => <span className="small">{r.stem ?? '—'}</span> },
    { key: 'points', title: t('teacher.reports.wrongPoints'), value: (r) => r.points, sortable: true, render: (r) => <span className="mono">{fmtNum(r.points)}</span> },
    { key: 'correctRate', title: t('teacher.reports.wrongRate'), value: (r) => r.correctRate, sortable: true, render: (r) => <span className="mono">{fmtRate(r.correctRate)}</span> },
    { key: 'wrong', title: t('teacher.reports.wrongCount'), value: (r) => r.wrong, sortable: true, render: (r) => <span className="mono">{r.wrong} / {r.answered}</span> },
    { key: 'topWrongAnswer', title: t('teacher.reports.wrongTop'), value: (r) => r.topWrongAnswer ?? '', sortable: true, render: (r) => r.topWrongAnswer ? <span className="mono">{r.topWrongAnswer} × {r.topWrongCount}</span> : <span className="muted">—</span> },
  ]

  const columns: Column<ReportRow>[] = [
    {
      key: 'offeringTitle',
      title: t('teacher.reports.colOffering'),
      value: (row) => row.offeringTitle,
      sortable: true,
      render: (row) => <b>{row.offeringTitle}</b>,
    },
    {
      key: 'className',
      title: t('teacher.reports.colClass'),
      value: (row) => row.className,
      sortable: true,
      render: (row) => row.className,
    },
    {
      key: 'dueAt',
      title: t('teacher.reports.colDue'),
      value: (row) => row.dueAt,
      sortable: true,
      render: (row) => new Date(row.dueAt).toLocaleDateString(),
    },
    {
      key: 'expected',
      title: t('teacher.reports.colExpected'),
      value: (row) => row.expected,
      sortable: true,
      render: (row) => <span className="mono">{row.expected}</span>,
    },
    {
      key: 'submitted',
      title: t('teacher.reports.colSubmitted'),
      value: (row) => row.submitted,
      sortable: true,
      render: (row) => (
        <span className="mono">
          {row.submitted} / {row.expected}
        </span>
      ),
    },
    {
      key: 'late',
      title: t('teacher.reports.colLate'),
      value: (row) => row.late,
      sortable: true,
      render: (row) =>
        row.late > 0 ? <Tag tone="amber">{row.late}</Tag> : <span className="mono">0</span>,
    },
    {
      key: 'onTimeRate',
      title: t('teacher.reports.colRate'),
      value: (row) => (row.onTimeRate === null ? -1 : row.onTimeRate),
      sortable: true,
      render: (row) => <span className="mono">{fmtRate(row.onTimeRate)}</span>,
    },
    {
      key: 'averageScore',
      title: t('teacher.reports.colAverage'),
      value: (row) => (row.averageScore === null ? -1 : row.averageScore),
      sortable: true,
      render: (row) =>
        row.averageScore === null ? (
          <Tag tone="gray">{t('teacher.summary.notPublished')}</Tag>
        ) : (
          <b className="mono">{fmtNum(row.averageScore)}</b>
        ),
    },
  ]

  return (
    <TeacherPage>
      <PageHeader title={t('teacher.reports.title')} sub={t('teacher.reports.sub')} />

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data && (
        <>
          <div className="card">
            <h2>{t('teacher.reports.filters')}</h2>
            <div className="filters">
              <label className="f">
                <b>{t('teacher.reports.offering')}</b>
                <select value={chosenOffering} onChange={(e) => setOffering(e.target.value)}>
                  <option value="all">{t('teacher.reports.allOfferings')}</option>
                  {offerings.map((offering) => (
                    <option key={offering.id} value={offering.id}>
                      {offering.paperTitle}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <div style={{ marginTop: 12 }}>
              <b className="small muted">{t('teacher.reports.classes')}</b>
              <div className="chips" style={{ marginTop: 7, marginBottom: 0 }}>
                {classes.map((c) => (
                  <button
                    key={c.id}
                    type="button"
                    className={`chip${chosenClasses.has(c.id) ? ' on' : ''}`}
                    onClick={() => toggleClass(c.id)}
                  >
                    {c.name}
                  </button>
                ))}
              </div>
              <div className="hint">{t('teacher.reports.classesHint')}</div>
            </div>
            <div className="hint">{t('teacher.reports.filterHint')}</div>
          </div>

          <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
            <h2 style={{ margin: 0 }}>
              {t('teacher.reports.tableTitle')}{' '}
              <small className="muted">
                {t('teacher.reports.tableCount', { n: filtered.length })}
              </small>
            </h2>
          </div>

          {rows.length === 0 ? (
            <Empty>{t('teacher.reports.empty')}</Empty>
          ) : (
            <DataTable
              rows={filtered}
              columns={columns}
              rowKey={(row) => row.key}
              sort={sort}
              page={page}
              pageSize={pageSize}
              onSortChange={setSort}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
              empty={t('teacher.reports.filteredEmpty')}
            />
          )}

          <div className="card">
            <h2>
              {t('teacher.reports.topTitle')} <small>{t('teacher.reports.topSub')}</small>
            </h2>
            <div className="row" style={{ marginBottom: 10 }}>
              <button type="button" className="btn pri" onClick={() => void queryWrongItems()} disabled={wrongLoading || offerings.length === 0}>
                {t('teacher.reports.topQuery')}
              </button>
              <label className="f" style={{ margin: 0, width: 130 }}>
                <b>{t('teacher.reports.wrongLimit')}</b>
                <select value={wrongLimit} onChange={(e) => setWrongLimit(Number(e.target.value))}>
                  {[5, 10, 20, 50].map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </label>
              <span className="hint" style={{ margin: 0 }}>
                {t('teacher.reports.filterHint')}
              </span>
            </div>
            {wrongLoading && <Loading label={t('teacher.common.loading')} />}
            {wrongError && <ErrorState error={wrongError} onRetry={() => void queryWrongItems()} />}
            {wrongReport && (
              <>
                <p className="small muted">{t('teacher.reports.wrongTotal', { n: wrongReport.totalItems })}</p>
                {wrongReport.items.length === 0 ? (
                  <Empty>{t('teacher.reports.wrongEmpty')}</Empty>
                ) : (
                  <DataTable
                    rows={wrongReport.items}
                    columns={wrongColumns}
                    rowKey={(r) => r.itemId}
                    page={wrongPage}
                    pageSize={wrongPageSize}
                    onPageChange={setWrongPage}
                    onPageSizeChange={(size) => { setWrongPageSize(size); setWrongPage(1) }}
                    empty={t('teacher.reports.wrongEmpty')}
                  />
                )}
              </>
            )}
          </div>

          <div className="card">
            <h2>{t('teacher.reports.moreTitle')}</h2>
            <ul style={{ margin: '0 0 10px', paddingLeft: 20, color: 'var(--muted)' }}>
              <li>{t('teacher.reports.moreDistribution')}</li>
              <li>{t('teacher.reports.moreTrend')}</li>
              <li>{t('teacher.reports.moreExport')}</li>
            </ul>
            <Tag tone="amber">{t('teacher.common.notConnected')}</Tag>
          </div>
        </>
      )}
    </TeacherPage>
  )
}
