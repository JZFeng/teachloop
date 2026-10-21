import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { api, fmtDateTime } from '../api/client'
import type { ClassView, OfferingView } from '../api/types'
import { DataTable } from '../components/DataTable'
import type { Column } from '../components/DataTable'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { useListState } from '../components/useListState'
import { classNamesText, PageSizePicker, Tag, TeacherPage, useApiData } from './common'

interface AssignmentsData {
  offerings: OfferingView[]
  classes: ClassView[]
}

/**
 * T240 / D-14 严格版：作业列表。
 * 只列 kind=ASSIGNMENT 的布置（作业自带内容、paperId 为 null）；考试仍在批改总览与试卷页。
 */
export default function AssignmentsPage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  const { sort, page, pageSize, setSort, setPage } = useListState(
    { key: 'dueAt', dir: 'desc' },
    20,
  )

  const { data, loading, error, reload } = useApiData<AssignmentsData>(async () => {
    const [offerings, classes] = await Promise.all([
      api.get<OfferingView[]>('/api/offerings'),
      api.get<ClassView[]>('/api/classes'),
    ])
    return { offerings, classes }
  }, [])

  const classNames = Object.fromEntries((data?.classes ?? []).map((c) => [c.id, c.name]))
  const assignments = (data?.offerings ?? []).filter((o) => o.kind === 'ASSIGNMENT')

  const columns: Column<OfferingView>[] = [
    {
      key: 'title',
      title: t('teacher.assignments.colTitle'),
      value: (row) => row.paperTitle,
      sortable: true,
      render: (row) => <b>{row.paperTitle}</b>,
    },
    {
      key: 'classes',
      title: t('teacher.assignments.colClasses'),
      value: (row) => classNamesText(row.classes.map((c) => c.classId), classNames),
      sortable: true,
      render: (row) => (
        <span>{classNamesText(row.classes.map((c) => c.classId), classNames)}</span>
      ),
    },
    {
      key: 'dueAt',
      title: t('teacher.assignments.colDue'),
      value: (row) => Date.parse(row.dueAt),
      sortable: true,
      render: (row) => <span className="mono">{fmtDateTime(row.dueAt)}</span>,
    },
    {
      key: 'submissions',
      title: t('teacher.assignments.colSubmitted'),
      value: (row) => row.submissionCount,
      sortable: true,
      render: (row) => (
        <div>
          <span className="mono">{row.submissionCount}</span>
          <div className="small muted">
            {t('teacher.common.classCount', { count: row.classCount })}
          </div>
        </div>
      ),
    },
    {
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
    {
      key: 'actions',
      title: t('teacher.common.actions'),
      value: () => '',
      render: (row) => (
        <div className="row" style={{ gap: 6 }} onClick={(e) => e.stopPropagation()}>
          <Link className="btn sm" to={`/grading/${row.id}`}>
            {t('teacher.assignments.grade')}
          </Link>
        </div>
      ),
    },
  ]

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
          <>
            <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
              <span className="small muted">{t('teacher.assignments.orderHint')}</span>
              <PageSizePicker pageSize={pageSize} />
            </div>
            <DataTable
              rows={assignments}
              columns={columns}
              rowKey={(row) => row.id}
              sort={sort}
              page={page}
              pageSize={pageSize}
              onSortChange={setSort}
              onPageChange={setPage}
              onRowClick={(row) => navigate(`/grading/${row.id}`)}
              empty={t('teacher.assignments.empty')}
            />
          </>
        ))}
    </TeacherPage>
  )
}
