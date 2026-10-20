import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { PaperView } from '../api/types'
import { DataTable } from '../components/DataTable'
import type { Column } from '../components/DataTable'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { useListState } from '../components/useListState'
import { PageSizePicker, Tag, TeacherPage, useApiData } from './common'

/**
 * 试卷库（T206 的前置 / T185 入口）。
 * 后端已按创建时间倒序返回；列表的排序与分页由 useListState 写进 URL。
 */
export default function PapersPage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  const { sort, page, pageSize, setSort, setPage } = useListState(undefined, 20)

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
      key: 'actions',
      title: t('teacher.common.actions'),
      value: () => '',
      render: (row) => (
        <div className="row" style={{ gap: 6 }} onClick={(e) => e.stopPropagation()}>
          <Link className="btn sm" to={`/papers/${row.id}`}>
            {t('teacher.common.edit')}
          </Link>
          <Link className="btn sm" to={`/offerings/new?paperId=${row.id}`}>
            {t('teacher.papers.assign')}
          </Link>
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
          <Link className="btn pri" to="/papers/new">
            {t('teacher.papers.new')}
          </Link>
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
              <PageSizePicker pageSize={pageSize} />
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
              onRowClick={(row) => navigate(`/papers/${row.id}`)}
              empty={t('teacher.common.empty')}
            />
          </>
        ))}
    </TeacherPage>
  )
}
