import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api, fmtDateTime } from '../api/client'
import type { ClassView, OfferingView, ReviewView } from '../api/types'
import { Button, DataTable, Empty, ErrorState, Loading, PageHeader, StatCard } from '../ui'
import type { Column } from '../ui'
import { useListState } from '../ui'
import {
  classNamesText,
  loadAggregates,
  OfferingStateTag,
  TeacherPage,
  useApiData,
} from './common'
import type { OfferingAggregate } from './common'

interface HomeData {
  offerings: OfferingView[]
  classes: ClassView[]
  queue: ReviewView[]
  aggregates: Record<string, OfferingAggregate>
}

/**
 * T180 教师首页：跨班级聚合，不需要先选班级。
 * 统计口径全部来自 /progress 与 /class-summary，点击数字进入已过滤的列表。
 */
export default function TeacherHomePage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(
    { key: 'dueAt', dir: 'desc' },
    10,
  )

  const { data, loading, error, reload } = useApiData<HomeData>(async () => {
    const [offerings, classes, queue] = await Promise.all([
      api.get<OfferingView[]>('/api/offerings'),
      api.get<ClassView[]>('/api/classes'),
      api.get<ReviewView[]>('/api/grading/queue'),
    ])
    return { offerings, classes, queue, aggregates: await loadAggregates(offerings) }
  }, [])

  const selectedClasses = new Set((params.get('classes') ?? '').split(',').filter(Boolean))

  const toggleClass = (classId: string) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      const chosen = new Set((next.get('classes') ?? '').split(',').filter(Boolean))
      if (chosen.has(classId)) chosen.delete(classId)
      else chosen.add(classId)
      if (chosen.size > 0) next.set('classes', [...chosen].join(','))
      else next.delete('classes')
      // AC-19.4：切换班级筛选不重置排序与分页。
      return next
    })
  }

  const classes = data?.classes ?? []
  const classNames = Object.fromEntries(classes.map((c) => [c.id, c.name]))
  const offerings = data?.offerings ?? []
  const aggregates = data?.aggregates ?? {}

  const filteredOfferings =
    selectedClasses.size === 0
      ? offerings
      : offerings.filter((o) => o.classes.some((c) => selectedClasses.has(c.classId)))

  const sum = (pick: (agg: OfferingAggregate) => number) =>
    offerings.reduce((acc, o) => {
      const agg = aggregates[o.id]
      return agg ? acc + pick(agg) : acc
    }, 0)

  const running = sum((a) => a.progress.running)
  const pendingPublish = offerings.reduce((acc, o) => {
    const agg = aggregates[o.id]
    return agg && !o.publishScores ? acc + agg.progress.done : acc
  }, 0)
  const unsubmitted = sum((a) => a.unsubmitted)
  const reviewCount = data?.queue.length ?? 0

  const columns: Column<OfferingView>[] = [
    {
      key: 'classes',
      title: t('teacher.home.colClasses'),
      value: (o) => o.classCount,
      sortable: true,
      render: (o) => (
        <div>
          <div>
            {classNamesText(
              o.classes.map((c) => c.classId),
              classNames,
              t('teacher.common.unknownClasses'),
            )}
          </div>
          <div className="small muted">
            {t('teacher.common.classCount', { count: o.classCount })}
          </div>
        </div>
      ),
    },
    {
      key: 'title',
      title: t('teacher.home.colTitle'),
      value: (o) => o.paperTitle,
      sortable: true,
      render: (o) => (
        <div>
          <b>{o.paperTitle}</b>
          <div className="small muted">{t(`teacher.kind.${o.kind}`)}</div>
        </div>
      ),
    },
    {
      key: 'dueAt',
      title: t('teacher.home.colDue'),
      value: (o) => o.dueAt,
      sortable: true,
      render: (o) => fmtDateTime(o.dueAt),
    },
    {
      key: 'submitted',
      title: t('teacher.home.colSubmitted'),
      value: (o) => aggregates[o.id]?.submitted ?? 0,
      sortable: true,
      render: (o) => {
        const agg = aggregates[o.id]
        if (!agg) return '—'
        return (
          <span className="mono">
            {agg.submitted} / {agg.expected}
          </span>
        )
      },
    },
    {
      key: 'state',
      title: t('teacher.home.colStatus'),
      value: (o) => aggregateStateRank(aggregates[o.id]),
      sortable: true,
      render: (o) => {
        const agg = aggregates[o.id]
        if (!agg) return '—'
        return (
          <OfferingStateTag
            progress={agg.progress}
            publishScores={o.publishScores}
            expected={agg.expected}
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
          <Button size="small" variant="text" onClick={() => navigate(`/grading/${o.id}`)}>
            {t('teacher.common.detail')}
          </Button>
          <Button size="small" variant="text" onClick={() => navigate(`/offerings/${o.id}/summary`)}>
            {t('teacher.common.summary')}
          </Button>
        </div>
      ),
    },
  ]

  return (
    <TeacherPage>
      <PageHeader title={t('teacher.home.title')} sub={t('teacher.home.sub')} />

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data && (
        <>
          <div className="stats">
            <StatCard
              value={reviewCount}
              label={t('teacher.home.stats.review')}
              sub={t('teacher.home.stats.reviewSub')}
              onClick={() => navigate('/review?status=PENDING')}
            />
            <StatCard
              value={running}
              label={t('teacher.home.stats.running')}
              sub={t('teacher.home.stats.runningSub')}
              onClick={() => navigate('/grading?state=running')}
            />
            <StatCard
              value={pendingPublish}
              label={t('teacher.home.stats.publish')}
              sub={t('teacher.home.stats.publishSub')}
              onClick={() => navigate('/grading?state=unpublished')}
            />
            <StatCard
              value={unsubmitted}
              label={t('teacher.home.stats.unsubmitted')}
              sub={t('teacher.home.stats.unsubmittedSub')}
              onClick={() => navigate('/grading?state=unsubmitted')}
            />
          </div>

          <div className="card">
            <h2>
              {t('teacher.home.classFilter')} <small>{t('teacher.home.classFilterSub')}</small>
            </h2>
            {classes.length === 0 ? (
              <p className="muted">{t('teacher.home.noClasses')}</p>
            ) : (
              <div className="chips" style={{ marginBottom: 0 }}>
                {classes.map((c) => (
                  <button
                    key={c.id}
                    type="button"
                    className={`chip${selectedClasses.has(c.id) ? ' on' : ''}`}
                    onClick={() => toggleClass(c.id)}
                  >
                    {c.name} ({c.memberCount})
                  </button>
                ))}
              </div>
            )}
            <div className="hint">{t('teacher.home.classFilterHint')}</div>
          </div>

          {offerings.length === 0 ? (
            <Empty>
              {t('teacher.home.empty')} <Link to="/papers">{t('teacher.home.emptyCta')}</Link>
            </Empty>
          ) : (
            <>
              <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
                <h2 style={{ margin: 0 }}>
                  {t('teacher.home.recent')}{' '}
                  <small className="muted">{t('teacher.home.recentSub')}</small>
                </h2>
              </div>
              <DataTable
                rows={filteredOfferings}
                columns={columns}
                rowKey={(o) => o.id}
                sort={sort}
                page={page}
                pageSize={pageSize}
                onSortChange={setSort}
                onPageChange={setPage}
                onPageSizeChange={setPageSize}
                onRowClick={(o) => navigate(`/grading/${o.id}`)}
                empty={t('teacher.home.filteredEmpty')}
              />
            </>
          )}
        </>
      )}
    </TeacherPage>
  )
}

/** 排序用：把派生状态映射成可比较的等级，避免按字符串排出来反直觉。 */
function aggregateStateRank(agg: OfferingAggregate | undefined): number {
  if (!agg) return 99
  if (agg.progress.failed > 0) return 0
  if (agg.progress.running > 0) return 1
  if (agg.progress.pending > 0) return 2
  if (agg.progress.total === 0) return 5
  if (agg.progress.done >= agg.progress.total) return 3
  return 4
}
