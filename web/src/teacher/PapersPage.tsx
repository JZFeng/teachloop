import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { PaperView } from '../api/types'
import { Button, DataTable, Empty, ErrorState, Loading, PageHeader } from '../ui'
import type { Column } from '../ui'
import { useListState } from '../ui'
import { Tag, TeacherPage, useApiData } from './common'

/**
 * 试卷库（T206 的前置 / T185 入口）。
 * 后端已按创建时间倒序返回；列表的排序与分页由 useListState 写进 URL。
 */
export default function PapersPage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(undefined, 10)

  const { data, loading, error, reload } = useApiData<PaperView[]>(
    () => api.get<PaperView[]>('/api/papers'),
    [],
  )

  const papers = data ?? []

  const columns: Column<PaperView>[] = [
    {
      key: 'title',
      title: t('teacher.papers.colTitle'),
      value: (row) => row.title,
      sortable: true,
      render: (row) => <b>{row.title}</b>,
    },
    {
      key: 'totalPoints',
      title: t('teacher.papers.colTotal'),
      value: (row) => row.totalPoints,
      sortable: true,
      render: (row) => <span className="mono">{row.totalPoints}</span>,
    },
    {
      key: 'duration',
      title: t('teacher.papers.colDuration'),
      value: (row) => row.defaultDurationMinutes ?? -1,
      sortable: true,
      render: (row) =>
        row.defaultDurationMinutes === null ? (
          <span className="muted">{t('teacher.papers.noLimit')}</span>
        ) : (
          t('teacher.common.minutes', { n: row.defaultDurationMinutes })
        ),
    },
    {
      key: 'version',
      title: t('teacher.papers.colVersion'),
      value: (row) => row.version,
      sortable: true,
      render: (row) => <span className="mono">v{row.version}</span>,
    },
    {
      key: 'status',
      title: t('teacher.papers.colStatus'),
      value: (row) => row.status,
      sortable: true,
      render: (row) => (
        <Tag tone={row.status === 'PUBLISHED' ? 'green' : 'gray'}>
          {t(`teacher.paperStatus.${row.status}`)}
        </Tag>
      ),
    },
    {
      key: 'assignmentCount',
      title: t('teacher.papers.colAssigned'),
      value: (row) => row.assignmentCount ?? 0,
      sortable: true,
      render: (row) => <span className="mono">{t('teacher.papers.assignedTimes', { n: row.assignmentCount ?? 0 })}</span>,
    },
    {
      key: 'actions',
      title: t('teacher.common.actions'),
      width: '320px',
      value: () => '',
      render: (row) => (
        <div className="row" style={{ gap: 6 }} onClick={(e) => e.stopPropagation()}>
          <Button size="small" variant="text" onClick={() => navigate(`/papers/${row.id}`)}>
            {t('teacher.common.edit')}
          </Button>
          <Button size="small" variant="text" onClick={() => navigate(`/offerings/new?paperId=${row.id}`)}>
            {t('teacher.papers.assign')}
          </Button>
          <Button size="small" variant="text" onClick={() => void api.post(`/api/papers/${row.id}/copy`).then(reload)}>
            {t('teacher.papers.copy')}
          </Button>
          <Button
            size="small"
            variant="text"
            disabled={row.status === 'ARCHIVED'}
            onClick={() => void api.post(`/api/papers/${row.id}/archive`).then(reload)}
          >
            {t('teacher.papers.archive')}
          </Button>
        </div>
      ),
    },
  ]

  return (
    <TeacherPage>
      <PageHeader
        title={t('teacher.papers.title')}
        sub={t('teacher.papers.sub')}
        actions={
          <div className="row">
            <Button variant="primary" onClick={() => navigate('/papers/new')}>
              {t('teacher.papers.new')}
            </Button>
          </div>
        }
      />

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data &&
        (papers.length === 0 ? (
          <Empty>
            {t('teacher.papers.empty')}{' '}
            <Link to="/papers/new">{t('teacher.papers.emptyCta')}</Link>
          </Empty>
        ) : (
          <>
            <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
              <span className="small muted">{t('teacher.papers.orderHint')}</span>
            </div>
            <DataTable
              rows={papers}
              columns={columns}
              rowKey={(row) => row.id}
              sort={sort}
              page={page}
              pageSize={pageSize}
              onSortChange={setSort}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
              onRowClick={(row) => navigate(`/papers/${row.id}`)}
              empty={t('teacher.common.empty')}
            />
          </>
        ))}
    </TeacherPage>
  )
}
