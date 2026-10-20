import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { ClassSummaryView, ClassView, OfferingView } from '../api/types'
import { DataTable } from '../components/DataTable'
import type { Column } from '../components/DataTable'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { useListState } from '../components/useListState'
import {
  fmtNum,
  fmtRate,
  PageSizePicker,
  Tag,
  TeacherPage,
  useApiData,
} from './common'

interface SummaryData {
  offering: OfferingView
  summary: ClassSummaryView[]
  classes: ClassView[]
}

/**
 * T191 班级汇总：一场考试挂多班时按班分别统计，不混算。
 * 平均分只统计已提交；publishScores=false 时后端返回 null，页面显示「未发布」。
 * 导出后端尚未接入，按钮禁用并明示。
 */
export default function ClassSummaryPage() {
  const { t } = useTranslation('translation')
  const { offeringId = '' } = useParams()
  const { sort, page, pageSize, setSort, setPage } = useListState(
    { key: 'className', dir: 'asc' },
    20,
  )

  const { data, loading, error, reload } = useApiData<SummaryData>(async () => {
    const [offering, summary, classes] = await Promise.all([
      api.get<OfferingView>(`/api/offerings/${offeringId}`),
      api.get<ClassSummaryView[]>(`/api/offerings/${offeringId}/class-summary`),
      api.get<ClassView[]>('/api/classes'),
    ])
    return { offering, summary, classes }
  }, [offeringId])

  const classNames = Object.fromEntries((data?.classes ?? []).map((c) => [c.id, c.name]))
  const rows = (data?.summary ?? []).map((row) => ({
    ...row,
    className: classNames[row.classId] ?? row.classId,
  }))
  const offering = data?.offering

  const columns: Column<(typeof rows)[number]>[] = [
    {
      key: 'className',
      title: t('teacher.summary.colClass'),
      value: (row) => row.className,
      sortable: true,
      render: (row) => <b>{row.className}</b>,
    },
    {
      key: 'expected',
      title: t('teacher.summary.colExpected'),
      value: (row) => Number(row.expected),
      sortable: true,
      render: (row) => <span className="mono">{row.expected}</span>,
    },
    {
      key: 'submitted',
      title: t('teacher.summary.colSubmitted'),
      value: (row) => Number(row.submitted),
      sortable: true,
      render: (row) => (
        <span className="mono">
          {row.submitted} / {row.expected}
        </span>
      ),
    },
    {
      key: 'onTime',
      title: t('teacher.summary.colOnTime'),
      value: (row) => Number(row.onTime),
      sortable: true,
      render: (row) => <span className="mono">{row.onTime}</span>,
    },
    {
      key: 'late',
      title: t('teacher.summary.colLate'),
      value: (row) => Number(row.late),
      sortable: true,
      render: (row) =>
        row.late > 0 ? <Tag tone="amber">{row.late}</Tag> : <span className="mono">0</span>,
    },
    {
      key: 'rejected',
      title: t('teacher.summary.colRejected'),
      value: (row) => Number(row.rejected),
      sortable: true,
      render: (row) =>
        row.rejected > 0 ? <Tag tone="red">{row.rejected}</Tag> : <span className="mono">0</span>,
    },
    {
      key: 'onTimeRate',
      title: t('teacher.summary.colRate'),
      value: (row) => (row.onTimeRate === null ? -1 : Number(row.onTimeRate)),
      sortable: true,
      render: (row) => <span className="mono">{fmtRate(row.onTimeRate)}</span>,
    },
    {
      key: 'averageScore',
      title: t('teacher.summary.colAverage'),
      value: (row) => (row.averageScore === null ? -1 : Number(row.averageScore)),
      sortable: true,
      render: (row) =>
        row.averageScore === null ? (
          <Tag tone="gray">{t('teacher.summary.notPublished')}</Tag>
        ) : (
          <b className="mono">{fmtNum(row.averageScore)}</b>
        ),
    },
  ]

  return (
    <TeacherPage>
      <div className="breadcrumb">
        <Link to="/grading">{t('teacher.grading.title')}</Link> /{' '}
        <Link to={`/grading/${offeringId}`}>{offering?.paperTitle ?? offeringId}</Link> /{' '}
        <b>{t('teacher.summary.title')}</b>
      </div>
      <PageHeader
        title={t('teacher.summary.title')}
        sub={
          offering
            ? t('teacher.summary.sub', { paper: offering.paperTitle })
            : t('teacher.summary.subLoading')
        }
      />

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data && offering && (
        <>
          {!offering.publishScores && (
            <div className="inline-note">{t('teacher.summary.scoresHidden')}</div>
          )}

          <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
            <h2 style={{ margin: 0 }}>
              {t('teacher.summary.tableTitle')}{' '}
              <small className="muted">{t('teacher.summary.tableSub')}</small>
            </h2>
            <PageSizePicker pageSize={pageSize} />
          </div>

          {rows.length === 0 ? (
            <Empty>{t('teacher.summary.empty')}</Empty>
          ) : (
            <DataTable
              rows={rows}
              columns={columns}
              rowKey={(row) => row.classId}
              sort={sort}
              page={page}
              pageSize={pageSize}
              onSortChange={setSort}
              onPageChange={setPage}
              empty={t('teacher.common.empty')}
            />
          )}

          <div className="card">
            <h2>{t('teacher.summary.exportTitle')}</h2>
            <div className="row">
              <button type="button" className="btn" disabled title={t('teacher.common.notConnected')}>
                {t('teacher.summary.exportMarkdown')}
              </button>
              <button type="button" className="btn" disabled title={t('teacher.common.notConnected')}>
                {t('teacher.summary.exportPdf')}
              </button>
              <button type="button" className="btn" disabled title={t('teacher.common.notConnected')}>
                {t('teacher.summary.exportExcel')}
              </button>
              <Tag tone="amber">{t('teacher.common.notConnected')}</Tag>
            </div>
            <div className="hint">{t('teacher.summary.exportHint')}</div>
          </div>

          <div className="hint">{t('teacher.summary.sameSource')}</div>
        </>
      )}
    </TeacherPage>
  )
}
