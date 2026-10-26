import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api, fmtDateTime } from '../api/client'
import type { ClassView, OfferingStatus, OfferingView } from '../api/types'
import {
  Button,
  ColumnPicker,
  DataTable,
  Search,
  TableToolbar,
  useColumnPrefs,
  useListState,
} from '../ui'
import type { Column } from '../ui'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { classNamesText, Tag, TeacherPage, useApiData } from './common'

interface AssignmentsData {
  offerings: OfferingView[]
  classes: ClassView[]
}

const DEFAULT_ORDER = ['title', 'classes', 'dueAt', 'submitted', 'status'] as const
type ColumnKey = (typeof DEFAULT_ORDER)[number]

/** 列配置存浏览器本地（AC-19.8）：作业列表没有后端 prefs 接口，也不值得为一个列开关加迁移。 */
const PREFS_KEY = 'teachloop.assignments.columns.v1'

/** 状态筛选只给真实存在的三档（PUBLISHED / DRAFT / CLOSED），与状态列同一个词表。 */
const FILTER_STATUSES: OfferingStatus[] = ['PUBLISHED', 'DRAFT', 'CLOSED']

const COLUMN_LABEL_KEY: Record<ColumnKey, string> = {
  title: 'teacher.assignments.colTitle',
  classes: 'teacher.assignments.colClasses',
  dueAt: 'teacher.assignments.colDue',
  submitted: 'teacher.assignments.colSubmitted',
  status: 'teacher.assignments.colStatus',
}

/**
 * T240 / D-14 严格版：作业列表。
 * 只列 kind=ASSIGNMENT 的布置（作业自带内容、paperId 为 null）；考试仍在批改总览与试卷页。
 *
 * 2026-10-26（用户要求）：table view 对齐「我的词库」——常驻搜索、筛选（班级 / 状态）、列配置。
 * 筛选与搜索写 URL（`q` / `classes` / `status`），切换筛选不丢排序与分页（AC-19.4）；
 * 筛选弹层、列配置弹层直接用 `ui/TableToolbar` 的标准件，不在这里另写一套。
 */
export default function AssignmentsPage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(
    { key: 'dueAt', dir: 'desc' },
    10,
  )
  const [columnsOpen, setColumnsOpen] = useState(false)
  const colPrefs = useColumnPrefs(PREFS_KEY, [...DEFAULT_ORDER])

  const { data, loading, error, reload } = useApiData<AssignmentsData>(async () => {
    const [offerings, classes] = await Promise.all([
      api.get<OfferingView[]>('/api/offerings'),
      api.get<ClassView[]>('/api/classes'),
    ])
    return { offerings, classes }
  }, [])

  const classNames = Object.fromEntries((data?.classes ?? []).map((c) => [c.id, c.name]))
  const assignments = (data?.offerings ?? []).filter((o) => o.kind === 'ASSIGNMENT')
  const classes = data?.classes ?? []

  const q = params.get('q') ?? ''
  // 搜索框的即时值与 URL 双轨：输入立刻过滤（不等一次 URL 往返），URL 变了（后退/前进）再同步回来。
  // 与词库页同一套写法，清空（allowClear / 全选删除）当场生效。
  const [search, setSearch] = useState(q)
  useEffect(() => { setSearch(q) }, [q])
  const selectedClasses = (params.get('classes') ?? '').split(',').filter(Boolean)
  const selectedStatuses = params.getAll('status').filter((value): value is OfferingStatus =>
    (FILTER_STATUSES as readonly string[]).includes(value),
  )
  const filterCount = selectedClasses.length + selectedStatuses.length

  /** 只改 URL，不动 sort/page 之外的参数；用 setSearchParams 的函数式写法避免连点时读到旧参数。 */
  const update = (mutate: (next: URLSearchParams) => void) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      mutate(next)
      return next
    }, { replace: true })
  }

  const keyword = search.trim().toLowerCase()
  const rows = assignments.filter((row) => {
    if (keyword && !row.paperTitle.toLowerCase().includes(keyword)) return false
    if (selectedClasses.length > 0 && !row.classes.some((c) => selectedClasses.includes(c.classId))) return false
    if (selectedStatuses.length > 0 && !selectedStatuses.includes(row.status)) return false
    return true
  })

  /** 「清空筛选」= 回到全量：关键字与两组筛选一起清（与首页、词库页同一个语义）。 */
  const resetAll = () => {
    setSearch('')
    update((prev) => {
      ;['q', 'classes', 'status'].forEach((key) => prev.delete(key))
    })
  }

  const allColumns = useMemo<Record<ColumnKey, Column<OfferingView>>>(() => ({
    title: {
      key: 'title',
      title: t('teacher.assignments.colTitle'),
      value: (row) => row.paperTitle,
      sortable: true,
      render: (row) => (
        <Link className="tl-row-link" to={`/grading/${row.id}`} onClick={(event) => event.stopPropagation()}>
          {row.paperTitle}
        </Link>
      ),
    },
    classes: {
      key: 'classes',
      title: t('teacher.assignments.colClasses'),
      value: (row) =>
        classNamesText(
          row.classes.map((c) => c.classId),
          classNames,
          t('teacher.common.unknownClasses'),
        ),
      sortable: true,
      render: (row) => (
        <span>
          {classNamesText(
            row.classes.map((c) => c.classId),
            classNames,
            t('teacher.common.unknownClasses'),
          )}
        </span>
      ),
    },
    dueAt: {
      key: 'dueAt',
      title: t('teacher.assignments.colDue'),
      value: (row) => Date.parse(row.dueAt),
      sortable: true,
      render: (row) => <span className="mono">{fmtDateTime(row.dueAt)}</span>,
    },
    submitted: {
      key: 'submitted',
      title: t('teacher.assignments.colSubmitted'),
      // 2026-10-26 用户要求：提交列写「已交/应交」。班级数不在这里重复第二行——班级列已经写了。
      // 排序按交齐比例：30/30 排在 1/30 前面，比按已交份数排更符合「谁先追」的判断。
      value: (row) => (row.expectedCount > 0 ? row.submissionCount / row.expectedCount : -1),
      sortable: true,
      render: (row) => (
        <span className="mono">
          {row.submissionCount}
          <span className="muted">/{row.expectedCount}</span>
        </span>
      ),
    },
    status: {
      key: 'status',
      title: t('teacher.assignments.colStatus'),
      value: (row) => row.status,
      sortable: true,
      render: (row) => (
        <Tag tone={row.status === 'PUBLISHED' ? 'green' : 'gray'}>
          {t(`teacher.offeringStatus.${row.status}`)}
        </Tag>
      ),
    },
  }), [classNames, t])

  const columns = colPrefs.visible
    .filter((key): key is ColumnKey => (DEFAULT_ORDER as readonly string[]).includes(key))
    .map((key) => allColumns[key])

  return (
    <TeacherPage>
      <PageHeader
        title={t('teacher.assignments.title')}
        sub={t('teacher.assignments.sub')}
        actions={
          <Link className="btn pri" to="/assignments/new">
            {t('teacher.assignments.new')}
          </Link>
        }
      />

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data &&
        (assignments.length === 0 ? (
          <Empty>
            {t('teacher.assignments.empty')}{' '}
            <Link to="/assignments/new">{t('teacher.assignments.emptyCta')}</Link>
          </Empty>
        ) : (
          <DataTable
            rows={rows}
            columns={columns}
            rowKey={(row) => row.id}
            sort={sort}
            page={page}
            pageSize={pageSize}
            onSortChange={setSort}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
            onRowClick={(row) => navigate(`/grading/${row.id}`)}
            toolbar={(
              <>
                <Search
                  className="tl-table-search"
                  value={search}
                  allowClear
                  placeholder={t('teacher.assignments.searchPlaceholder')}
                  onChange={(event) => {
                    const value = event.target.value
                    setSearch(value)
                    update((next) => {
                      if (value) next.set('q', value)
                      else next.delete('q')
                      next.set('page', '1')
                    })
                  }}
                  onSearch={(value) => {
                    setSearch(value)
                    update((next) => {
                      if (value) next.set('q', value)
                      else next.delete('q')
                      next.set('page', '1')
                    })
                  }}
                />
                <TableToolbar
                  groups={[
                    {
                      key: 'classes',
                      label: t('teacher.assignments.filterClasses'),
                      options: classes.map((c) => ({ value: c.id, label: `${c.name} (${c.memberCount})` })),
                    },
                    {
                      key: 'status',
                      label: t('teacher.assignments.filterStates'),
                      options: FILTER_STATUSES.map((status) => ({
                        value: status,
                        label: t(`teacher.offeringStatus.${status}`),
                      })),
                    },
                  ]}
                  value={{ classes: selectedClasses, status: selectedStatuses }}
                  onChange={(next) => update((prev) => {
                    prev.delete('classes')
                    prev.delete('status')
                    const picked = next.classes ?? []
                    if (picked.length > 0) prev.set('classes', picked.join(','))
                    for (const status of next.status ?? []) prev.append('status', status)
                    prev.set('page', '1')
                  })}
                  columnPicker={<Button onClick={() => setColumnsOpen(true)}>{t('common.columns')}</Button>}
                  onReset={resetAll}
                />
              </>
            )}
            empty={
              filterCount > 0 || keyword
                ? t('teacher.assignments.noMatch')
                : t('teacher.assignments.empty')
            }
          />
        ))}

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
