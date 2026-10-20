import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api, fmtDateTime } from '../api/client'
import type { ClassView, OfferingView } from '../api/types'
import { DataTable } from '../components/DataTable'
import type { Column } from '../components/DataTable'
import { Empty, ErrorState, Loading, PageHeader, StatCard } from '../components/States'
import { useListState } from '../components/useListState'
import {
  classNamesText,
  loadAggregates,
  OfferingStateTag,
  PageSizePicker,
  Tag,
  TeacherPage,
  useApiData,
} from './common'
import type { OfferingAggregate } from './common'

interface OverviewData {
  offerings: OfferingView[]
  classes: ClassView[]
  aggregates: Record<string, OfferingAggregate>
}

const FILTERS = ['all', 'done', 'failed', 'pending', 'unsubmitted'] as const
type Filter = (typeof FILTERS)[number]

/**
 * T181 / AC-20.1–AC-20.7：统计卡片 + 作业级表格。
 * 过滤条件写进 URL（state），排序与分页由 useListState 写 URL。
 */
export default function GradingOverviewPage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const { sort, page, pageSize, setSort, setPage } = useListState(
    { key: 'dueAt', dir: 'desc' },
    20,
  )

  const { data, loading, error, reload } = useApiData<OverviewData>(async () => {
    const [offerings, classes] = await Promise.all([
      api.get<OfferingView[]>('/api/offerings'),
      api.get<ClassView[]>('/api/classes'),
    ])
    return { offerings, classes, aggregates: await loadAggregates(offerings) }
  }, [])

  const filter = normalizeFilter(params.get('state'))
  const setFilter = (next: Filter) => {
    setParams((prev) => {
      const updated = new URLSearchParams(prev)
      if (next === 'all') updated.delete('state')
      else updated.set('state', next)
      updated.delete('page')
      return updated
    })
  }

  const offerings = data?.offerings ?? []
  const aggregates = data?.aggregates ?? {}
  const classNames = Object.fromEntries((data?.classes ?? []).map((c) => [c.id, c.name]))

  const sum = (pick: (agg: OfferingAggregate) => number) =>
    offerings.reduce((acc, o) => {
      const agg = aggregates[o.id]
      return agg ? acc + pick(agg) : acc
    }, 0)

  const totalDone = sum((a) => a.progress.done)
  const totalFailed = sum((a) => a.progress.failed)
  const totalPending = sum((a) => a.progress.pending)
  const totalRunning = sum((a) => a.progress.running)
  const totalUnsubmitted = sum((a) => a.unsubmitted)

  const filtered = offerings.filter((o) => {
    const agg = aggregates[o.id]
    if (!agg) return false
    switch (filter) {
      case 'done':
        return agg.progress.done > 0
      case 'failed':
        return agg.progress.failed > 0
      case 'pending':
        return agg.progress.pending > 0
      case 'unsubmitted':
        return agg.unsubmitted > 0
      case 'running':
        return agg.progress.running > 0
      case 'unpublished':
        return agg.progress.done > 0 && !o.publishScores
      default:
        return true
    }
  })

  const columns: Column<OfferingView>[] = [
    {
      key: 'title',
      title: t('teacher.grading.colTitle'),
      value: (o) => o.paperTitle,
      sortable: true,
      render: (o) => (
        <div>
          <b>{o.paperTitle}</b>
          <div className="small muted">
            {classNamesText(o.classes.map((c) => c.classId), classNames)}
          </div>
        </div>
      ),
    },
    {
      key: 'kind',
      title: t('teacher.grading.colKind'),
      value: (o) => o.kind,
      sortable: true,
      render: (o) => (
        <Tag tone={o.kind === 'EXAM' ? 'blue' : 'gray'}>{t(`teacher.kind.${o.kind}`)}</Tag>
      ),
    },
    {
      key: 'classCount',
      title: t('teacher.grading.colClassCount'),
      value: (o) => o.classCount,
      sortable: true,
      render: (o) => o.classCount,
    },
    {
      key: 'dueAt',
      title: t('teacher.grading.colDue'),
      value: (o) => o.dueAt,
      sortable: true,
      render: (o) => fmtDateTime(o.dueAt),
    },
    {
      key: 'submitted',
      title: t('teacher.grading.colSubmitted'),
      value: (o) => aggregates[o.id]?.submitted ?? 0,
      sortable: true,
      render: (o) => {
        const agg = aggregates[o.id]
        return agg ? (
          <span className="mono">
            {agg.submitted} / {agg.expected}
          </span>
        ) : (
          '—'
        )
      },
    },
    {
      key: 'state',
      title: t('teacher.grading.colState'),
      value: (o) => stateRank(aggregates[o.id]),
      sortable: true,
      render: (o) => {
        const agg = aggregates[o.id]
        if (!agg) return '—'
        const p = agg.progress
        return (
          <div>
            <div className="stat-row">
              <Tag tone="green">
                {t('teacher.grading.doneN', { n: p.done })}
              </Tag>
              <Tag tone={p.running > 0 ? 'blue' : 'gray'}>
                {t('teacher.grading.runningN', { n: p.running })}
              </Tag>
              <Tag tone={p.pending > 0 ? 'amber' : 'gray'}>
                {t('teacher.grading.pendingN', { n: p.pending })}
              </Tag>
              <Tag tone={p.failed > 0 ? 'red' : 'gray'}>
                {t('teacher.grading.failedN', { n: p.failed })}
              </Tag>
            </div>
            <div style={{ marginTop: 4 }}>
              <OfferingStateTag
                progress={p}
                publishScores={o.publishScores}
                expected={agg.expected}
                rejected={agg.rejected}
              />
            </div>
          </div>
        )
      },
    },
    {
      key: 'actions',
      title: t('teacher.common.actions'),
      value: () => '',
      render: (o) => (
        <div className="row" style={{ gap: 6 }} onClick={(e) => e.stopPropagation()}>
          <Link className="btn sm" to={`/grading/${o.id}`}>
            {t('teacher.common.detail')}
          </Link>
          <Link className="btn sm" to={`/offerings/${o.id}/summary`}>
            {t('teacher.common.summary')}
          </Link>
        </div>
      ),
    },
  ]

  const filterLabel = (key: Filter) => t(`teacher.grading.filters.${key}`)

  return (
    <TeacherPage>
      <PageHeader title={t('teacher.grading.title')} sub={t('teacher.grading.sub')} />

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data && (
        <>
          <div className="stats">
            <StatCard
              n={totalDone}
              label={t('teacher.grading.stats.done')}
              sub={totalRunning > 0 ? t('teacher.grading.stats.runningSub', { n: totalRunning }) : undefined}
              onClick={() => setFilter('done')}
            />
            <StatCard
              n={totalFailed}
              label={t('teacher.grading.stats.failed')}
              sub={t('teacher.grading.stats.failedSub')}
              onClick={() => setFilter('failed')}
            />
            <StatCard
              n={totalPending}
              label={t('teacher.grading.stats.pending')}
              onClick={() => setFilter('pending')}
            />
            <StatCard
              n={totalUnsubmitted}
              label={t('teacher.grading.stats.unsubmitted')}
              sub={t('teacher.grading.stats.unsubmittedSub')}
              onClick={() => setFilter('unsubmitted')}
            />
          </div>

          <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
            <h2 style={{ margin: 0 }}>
              {t('teacher.grading.tableTitle')}{' '}
              <small className="muted">{t('teacher.grading.tableSub')}</small>
            </h2>
            <PageSizePicker pageSize={pageSize} />
          </div>

          <div className="card" style={{ padding: '10px 14px' }}>
            <div className="row">
              <span className="small muted">{t('teacher.grading.filterLabel')}</span>
              {FILTERS.map((key) => (
                <button
                  key={key}
                  type="button"
                  className={`chip${filter === key ? ' on' : ''}`}
                  onClick={() => setFilter(key)}
                >
                  {filterLabel(key)}
                </button>
              ))}
              {filter === 'running' || filter === 'unpublished' ? (
                <span className="tag blue">
                  {t(`teacher.grading.filters.${filter}`)}
                  <button
                    type="button"
                    className="btn sm"
                    style={{ marginLeft: 6, padding: '0 6px' }}
                    onClick={() => setFilter('all')}
                  >
                    ×
                  </button>
                </span>
              ) : null}
              <span className="small muted" style={{ marginLeft: 'auto' }}>
                {t('teacher.grading.filteredCount', { shown: filtered.length, total: offerings.length })}
              </span>
            </div>
            {filter !== 'all' && (
              <div className="hint" style={{ marginTop: 4 }}>
                {t('teacher.grading.filterWritesUrl')}
              </div>
            )}
          </div>

          {offerings.length === 0 ? (
            <Empty>{t('teacher.grading.empty')}</Empty>
          ) : (
            <DataTable
              rows={filtered}
              columns={columns}
              rowKey={(o) => o.id}
              sort={sort}
              page={page}
              pageSize={pageSize}
              onSortChange={setSort}
              onPageChange={setPage}
              onRowClick={(o) => navigate(`/grading/${o.id}`)}
              empty={t('teacher.grading.filteredEmpty')}
            />
          )}
        </>
      )}
    </TeacherPage>
  )
}

function normalizeFilter(raw: string | null): Filter | 'running' | 'unpublished' {
  if (raw === 'running' || raw === 'unpublished') return raw
  if (raw === 'done' || raw === 'failed' || raw === 'pending' || raw === 'unsubmitted') return raw
  return 'all'
}

function stateRank(agg: OfferingAggregate | undefined): number {
  if (!agg) return 99
  if (agg.progress.failed > 0) return 0
  if (agg.progress.running > 0) return 1
  if (agg.progress.pending > 0) return 2
  if (agg.progress.total === 0) return 5
  if (agg.progress.done >= agg.progress.total) return 3
  return 4
}
