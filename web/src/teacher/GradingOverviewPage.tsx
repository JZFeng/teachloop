import { useTranslation } from 'react-i18next'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, fmtDateTime } from '../api/client'
import type { ClassView, OfferingView } from '../api/types'
import { Button, DataTable, Empty, ErrorState, Loading, PageHeader, StatCard, Segmented } from '../ui'
import type { Column } from '../ui'
import { useListState } from '../ui'
import { AiCircuitBanner } from '../components/AiCircuitBanner'
import {
  classNamesText,
  loadAggregates,
  OfferingStateTag,
  Tag,
  TeacherPage,
  useApiData,
} from './common'
import { offeringState, offeringStateRank } from './offeringState'
import type { OfferingAggregate } from './common'

interface OverviewData {
  offerings: OfferingView[]
  classes: ClassView[]
  aggregates: Record<string, OfferingAggregate>
}

// 决策 025：pending（排到队里）与 running（在跑）对老师是同一个动作——去作业详情页推一把，
// 所以并成一档「等 AI 批」，不再让老师区分工具内部的两种状态。
const FILTERS = ['all', 'done', 'failed', 'grading', 'unsubmitted'] as const
type Filter = (typeof FILTERS)[number]

/**
 * T181 / AC-20.1–AC-20.7：统计卡片 + 作业级表格，过滤条件写进 URL。
 *
 * 2026-10-21（UI 标准）：过滤条从一排 chip 换成 Segmented；每行原来并排 4 个
 * 状态标签，改成「一个进度条 + 一个状态标签」——4 个标签在窄列里会折成两行，
 * 反而看不出「批到哪儿了」。
 */
export default function GradingOverviewPage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(
    { key: 'dueAt', dir: 'desc' },
    10,
  )

  const { data, loading, error, reload } = useApiData<OverviewData>(async () => {
    const [offerings, classes] = await Promise.all([
      api.get<OfferingView[]>('/api/offerings'),
      api.get<ClassView[]>('/api/classes'),
    ])
    return { offerings, classes, aggregates: await loadAggregates(offerings) }
  }, [])

  const filter = normalizeFilter(params.get('state'))
  const setFilter = (next: Filter | 'unpublished') => {
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
  const totalPending = sum((a) => a.progress.pending + a.progress.running)
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
      case 'grading':
        return agg.progress.pending + agg.progress.running > 0
      case 'unsubmitted':
        return agg.unsubmitted > 0
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
            {classNamesText(
              o.classes.map((c) => c.classId),
              classNames,
              t('teacher.common.unknownClasses'),
            )}
          </div>
        </div>
      ),
    },
    {
      key: 'kind',
      title: t('teacher.grading.colKind'),
      value: (o) => o.kind,
      width: '92px',
      sortable: true,
      render: (o) => <Tag tone={o.kind === 'EXAM' ? 'blue' : 'gray'}>{t(`teacher.kind.${o.kind}`)}</Tag>,
    },
    {
      key: 'dueAt',
      title: t('teacher.grading.colDue'),
      value: (o) => o.dueAt,
      width: '150px',
      sortable: true,
      render: (o) => fmtDateTime(o.dueAt),
    },
    {
      key: 'progress',
      title: t('teacher.grading.colSubmitted'),
      value: (o) => {
        const agg = aggregates[o.id]
        return agg && agg.expected > 0 ? agg.submitted / agg.expected : 0
      },
      width: '150px',
      sortable: true,
      render: (o) => {
        const agg = aggregates[o.id]
        if (!agg) return '—'
        const p = agg.progress
        const percent = agg.expected > 0 ? Math.round((agg.submitted / agg.expected) * 100) : 0
        return (
          <div className="tl-progress">
            <div className="tl-progress-head">
              <span className="mono">
                {agg.submitted} / {agg.expected}
              </span>
              <span className="small muted">{percent}%</span>
            </div>
            <div className="tl-progress-bar">
              <i style={{ width: `${Math.min(100, percent)}%` }} />
            </div>
            {p.running > 0 || p.failed > 0 ? (
              <div className="tl-progress-note">
                {p.running > 0 && <span>{t('teacher.grading.runningN', { n: p.running })}</span>}
                {p.failed > 0 && (
                  <span className="tl-progress-failed">{t('teacher.grading.failedN', { n: p.failed })}</span>
                )}
              </div>
            ) : null}
          </div>
        )
      },
    },
    {
      key: 'state',
      title: t('teacher.grading.colState'),
      value: (o) => {
        const agg = aggregates[o.id]
        if (!agg) return 99
        return offeringStateRank(
          offeringState({
            progress: agg.progress,
            publishScores: o.publishScores,
            expected: agg.expected,
            unsubmitted: agg.unsubmitted,
            rejected: agg.rejected,
          }),
        )
      },
      width: '120px',
      sortable: true,
      render: (o) => {
        const agg = aggregates[o.id]
        if (!agg) return '—'
        return (
          <OfferingStateTag
            progress={agg.progress}
            publishScores={o.publishScores}
            expected={agg.expected}
            unsubmitted={agg.unsubmitted}
            rejected={agg.rejected}
          />
        )
      },
    },
    {
      key: 'actions',
      title: t('teacher.common.actions'),
      width: '132px',
      value: () => '',
      render: (o) => (
        <div className="row" style={{ gap: 6 }} onClick={(e) => e.stopPropagation()}>
          <Button size="small" onClick={() => navigate(`/grading/${o.id}`)}>
            {t('teacher.common.detail')}
          </Button>
          <Button size="small" variant="text" onClick={() => navigate(`/offerings/${o.id}/summary`)}>
            {t('teacher.common.summary')}
          </Button>
        </div>
      ),
    },
  ]

  const filterLabel = (key: Filter) => t(`teacher.grading.filters.${key}`)

  return (
    <TeacherPage>
      <PageHeader title={t('teacher.grading.title')} sub={t('teacher.grading.sub')} />
      <AiCircuitBanner />

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data && (
        <>
          <div className="stats">
            <StatCard
              value={totalDone}
              label={t('teacher.grading.stats.done')}
              sub={totalRunning > 0 ? t('teacher.grading.stats.runningSub', { n: totalRunning }) : undefined}
              active={filter === 'done'}
              onClick={() => setFilter(filter === 'done' ? 'all' : 'done')}
            />
            <StatCard
              value={totalFailed}
              label={t('teacher.grading.stats.failed')}
              sub={t('teacher.grading.stats.failedSub')}
              active={filter === 'failed'}
              onClick={() => setFilter(filter === 'failed' ? 'all' : 'failed')}
            />
            <StatCard
              value={totalPending}
              label={t('teacher.grading.stats.grading')}
              active={filter === 'grading'}
              onClick={() => setFilter(filter === 'grading' ? 'all' : 'grading')}
            />
            <StatCard
              value={totalUnsubmitted}
              label={t('teacher.grading.stats.unsubmitted')}
              sub={t('teacher.grading.stats.unsubmittedSub')}
              active={filter === 'unsubmitted'}
              onClick={() => setFilter(filter === 'unsubmitted' ? 'all' : 'unsubmitted')}
            />
          </div>

          <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
            <h2 style={{ margin: 0 }}>
              {t('teacher.grading.tableTitle')}{' '}
              <small className="muted">{t('teacher.grading.tableSub')}</small>
            </h2>
          </div>

          <div className="card" style={{ padding: '10px 14px' }}>
            <div className="row">
              <span className="small muted">{t('teacher.grading.filterLabel')}</span>
              <Segmented
                size="small"
                value={filter}
                onChange={(value) => setFilter(value as Filter | 'unpublished')}
                options={FILTERS.map((key) => ({ value: key, label: filterLabel(key) }))}
              />
              <span className="small muted" style={{ marginLeft: 'auto' }}>
                {t('teacher.grading.filteredCount', { shown: filtered.length, total: offerings.length })}
              </span>
            </div>
            {filter !== 'all' && (
              <div className="hint" style={{ marginTop: 6 }}>
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
              onPageSizeChange={setPageSize}
              onRowClick={(o) => navigate(`/grading/${o.id}`)}
              empty={t('teacher.grading.filteredEmpty')}
            />
          )}
        </>
      )}
    </TeacherPage>
  )
}

function normalizeFilter(raw: string | null): Filter | 'unpublished' {
  // 旧链接（首页「批中」卡曾经跳 ?state=running）与旧书签仍然要能打开：
  // pending / running 都归到合并后的 grading。
  if (raw === 'pending' || raw === 'running') return 'grading'
  if (raw === 'unpublished') return raw
  if (raw === 'done' || raw === 'failed' || raw === 'grading' || raw === 'unsubmitted') return raw
  return 'all'
}

