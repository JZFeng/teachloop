import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api, fmtDateTime } from '../api/client'
import type { ClassView, OfferingView, ReviewView } from '../api/types'
import {
  Button,
  ColumnPicker,
  DataTable,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  StatCard,
  TableToolbar,
  useColumnPrefs,
} from '../ui'
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
import { offeringState, offeringStateRank } from './offeringState'

interface HomeData {
  offerings: OfferingView[]
  classes: ClassView[]
  queue: ReviewView[]
  aggregates: Record<string, OfferingAggregate>
}

/** 列顺序默认值：作业是主题，所以排第一（AC-19.7）。 */
const DEFAULT_ORDER = ['title', 'classes', 'dueAt', 'submitted', 'state'] as const
type ColumnKey = (typeof DEFAULT_ORDER)[number]
const COLUMN_LABEL_KEY: Record<ColumnKey, string> = {
  title: 'teacher.home.colTitle',
  classes: 'teacher.home.colClasses',
  dueAt: 'teacher.home.colDue',
  submitted: 'teacher.home.colSubmitted',
  state: 'teacher.home.colStatus',
}

/** 申请状态筛选用的四档，与 offeringState 的取值一一对应（不新造词）。 */
const STATE_FILTERS = ['uncollected', 'grading', 'to_publish', 'published'] as const
type StateFilter = (typeof STATE_FILTERS)[number]

/**
 * 列配置存浏览器本地（AC-19.8），读写交给 ui 的 `useColumnPrefs`。
 * 词库的列配置存在后端 `/api/vocab/prefs`；首页没有对应接口，也不值得为一个列开关加迁移。
 */
const PREFS_KEY = 'teachloop.home.columns.v1'

/**
 * T180 教师首页：跨班级聚合，不需要先选班级。
 * 统计口径全部来自 /progress 与 /class-summary，点击数字进入已过滤的列表。
 *
 * 2026-10-25（用户拍板）：首页只做 overview——
 *   ① 班级筛选从「一张卡片」收进表格工具栏（AC-19.3 修订）；
 *   ② 「最近作业」以作业为首列，可筛选（班级 / 状态）、可配置列（AC-19.7 / AC-19.8）；
 *   ③ 行内不再放「详情 / 汇总」：点行进详情，汇总在作业详情页里切换（AC-25.1 / AC-25.6）。
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

  const [columnsOpen, setColumnsOpen] = useState(false)
  const colPrefs = useColumnPrefs(PREFS_KEY, [...DEFAULT_ORDER])

  const selectedClasses = (params.get('classes') ?? '').split(',').filter(Boolean)
  const selectedStates = params.getAll('state').filter((value): value is StateFilter =>
    (STATE_FILTERS as readonly string[]).includes(value),
  )

  /** 只改 URL，不动 sort/page：AC-19.4 要求切换筛选不丢排序与分页（DataTable 会自己夹紧页码）。 */
  const update = (mutate: (next: URLSearchParams) => void) => {
    const next = new URLSearchParams(params)
    mutate(next)
    setParams(next, { replace: true })
  }

  /** 两组筛选 → URL：班级用逗号（沿用旧链接），状态用多值 `state=`。 */
  const applyFilters = (next: Record<string, string[]>) => {
    update((search) => {
      search.delete('classes')
      search.delete('state')
      const classes = next.classes ?? []
      if (classes.length > 0) search.set('classes', classes.join(','))
      ;(next.state ?? []).forEach((value) => search.append('state', value))
    })
  }

  const classes = data?.classes ?? []
  const classNames = Object.fromEntries(classes.map((c) => [c.id, c.name]))
  const offerings = data?.offerings ?? []
  const aggregates = data?.aggregates ?? {}

  const stateOf = (o: OfferingView) => {
    const agg = aggregates[o.id]
    if (!agg) return null
    return offeringState({
      progress: agg.progress,
      publishScores: o.publishScores,
      expected: agg.expected,
      unsubmitted: agg.unsubmitted,
      rejected: agg.rejected,
    })
  }

  const filteredOfferings = offerings.filter((o) => {
    if (selectedClasses.length > 0 && !o.classes.some((c) => selectedClasses.includes(c.classId))) {
      return false
    }
    if (selectedStates.length > 0) {
      const view = stateOf(o)
      if (!view || !selectedStates.includes(view.state)) return false
    }
    return true
  })

  const filterCount = selectedClasses.length + selectedStates.length

  const sum = (pick: (agg: OfferingAggregate) => number) =>
    offerings.reduce((acc, o) => {
      const agg = aggregates[o.id]
      return agg ? acc + pick(agg) : acc
    }, 0)

  const pendingPublish = offerings.reduce((acc, o) => {
    const agg = aggregates[o.id]
    return agg && !o.publishScores ? acc + agg.progress.done : acc
  }, 0)
  const unsubmitted = sum((a) => a.unsubmitted)
  const reviewCount = data?.queue.length ?? 0

  const allColumns = useMemo<Record<ColumnKey, Column<OfferingView>>>(() => ({
    // 作业列第一：表名是「最近作业」，主题是作业；班级是维度不是主语（AC-19.7）。
    title: {
      key: 'title',
      title: t('teacher.home.colTitle'),
      value: (o) => o.paperTitle,
      sortable: true,
      render: (o) => (
        <div>
          <Link className="tl-row-link" to={`/grading/${o.id}`} onClick={(event) => event.stopPropagation()}>
            {o.paperTitle}
          </Link>
          <div className="small muted">{t(`teacher.kind.${o.kind}`)}</div>
        </div>
      ),
    },
    classes: {
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
          {/* 单班时不重复「1 个班」：班级名已经写在那儿了。 */}
          {o.classCount > 1 && (
            <div className="small muted">
              {t('teacher.common.classCount', { count: o.classCount })}
            </div>
          )}
        </div>
      ),
    },
    dueAt: {
      key: 'dueAt',
      title: t('teacher.home.colDue'),
      value: (o) => o.dueAt,
      sortable: true,
      render: (o) => fmtDateTime(o.dueAt),
    },
    submitted: {
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
    state: {
      key: 'state',
      title: t('teacher.home.colStatus'),
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
  }), [aggregates, classNames, t])

  const columns = colPrefs.visible
    .filter((key): key is ColumnKey => (DEFAULT_ORDER as readonly string[]).includes(key))
    .map((key) => allColumns[key])

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
                toolbar={(
                  <TableToolbar
                    groups={[
                      {
                        key: 'classes',
                        label: t('teacher.home.filterClasses'),
                        options: classes.map((c) => ({ value: c.id, label: `${c.name} (${c.memberCount})` })),
                      },
                      {
                        key: 'state',
                        label: t('teacher.home.filterStates'),
                        options: STATE_FILTERS.map((state) => ({
                          value: state,
                          label: t(`teacher.home.stateFilter.${state}`),
                        })),
                      },
                    ]}
                    value={{ classes: selectedClasses, state: selectedStates }}
                    onChange={applyFilters}
                    columnPicker={<Button onClick={() => setColumnsOpen(true)}>{t('common.columns')}</Button>}
                  />
                )}
                empty={filterCount > 0 ? t('teacher.home.filteredEmpty') : t('teacher.home.empty')}
              />
            </>
          )}
        </>
      )}

      <ColumnPicker
        open={columnsOpen}
        onClose={() => setColumnsOpen(false)}
        order={colPrefs.order}
        hidden={colPrefs.hidden}
        labels={Object.fromEntries(DEFAULT_ORDER.map((key) => [key, t(COLUMN_LABEL_KEY[key])]))}
        onToggle={colPrefs.toggle}
        onMove={colPrefs.move}
      />
    </TeacherPage>
  )
}
