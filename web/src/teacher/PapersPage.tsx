import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { PaperView } from '../api/types'
import { Button, DataTable, Empty, ErrorState, Loading, PageHeader, RowActions } from '../ui'
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
      render: (row) => (
        <Link className="tl-row-link" to={`/papers/${row.id}`} onClick={(event) => event.stopPropagation()}>
          {row.title}
        </Link>
      ),
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
      // 120px = 1 个主按钮 + 「⋯」；原来的 4 个按钮要 320px，且加一个动作就再折行（AC-25.1）。
      width: '120px',
      value: () => '',
      render: (row) => (
        <RowActions
          primary={{
            key: 'assign',
            label: t('teacher.papers.assign'),
            onClick: () => navigate(`/offerings/new?paperId=${row.id}`),
          }}
          items={[
            {
              key: 'copy',
              label: t('teacher.papers.copy'),
              onClick: () => void api.post(`/api/papers/${row.id}/copy`).then(reload),
            },
            {
              key: 'archive',
              label: t('teacher.papers.archive'),
              disabled: row.status === 'ARCHIVED',
              // AC-25.5：禁用原因必须在菜单里看得见。
              reason: row.status === 'ARCHIVED' ? t('teacher.papers.archivedHint') : undefined,
              onClick: () => void api.post(`/api/papers/${row.id}/archive`).then(reload),
            },
          ]}
        />
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
