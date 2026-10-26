import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { api, fmtDateTime } from '../api/client'
import type { ClassView, OfferingView } from '../api/types'
import { DataTable, useListState } from '../ui'
import type { Column } from '../ui'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { classNamesText, Tag, TeacherPage, useApiData } from './common'

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
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(
    { key: 'dueAt', dir: 'desc' },
    10,
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
      render: (row) => (
        <Link className="tl-row-link" to={`/grading/${row.id}`} onClick={(event) => event.stopPropagation()}>
          {row.paperTitle}
        </Link>
      ),
    },
    {
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
      // 2026-10-26 用户要求：提交列写「已交/应交」。班级数不在这里重复第二行——左侧班级列已经写了。
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
              onPageSizeChange={setPageSize}
              onRowClick={(row) => navigate(`/grading/${row.id}`)}
              empty={t('teacher.assignments.empty')}
            />
          </>
        ))}
    </TeacherPage>
  )
}
