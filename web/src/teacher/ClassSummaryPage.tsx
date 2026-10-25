import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { ClassSummaryView, ClassView, OfferingView } from '../api/types'
import { DataTable, useListState } from '../ui'
import type { Column } from '../ui'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import {
  fmtNum,
  fmtRate,
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
 * Markdown 与逐份反馈 ZIP 已接入；PDF / Excel 需要新增依赖，按钮禁用并明示。
 */
export default function ClassSummaryPage() {
  const { t, i18n } = useTranslation('translation')
  const lang = i18n.language.startsWith('zh') ? 'zh-CN' : 'en'
  const { offeringId = '' } = useParams()
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(
    { key: 'className', dir: 'asc' },
    10,
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
              onPageSizeChange={setPageSize}
              empty={t('teacher.common.empty')}
            />
          )}

          <div className="card">
            <h2>{t('teacher.summary.distributionTitle')}</h2>
            <div className="hint" style={{ marginBottom: 10 }}>{t('teacher.summary.distributionHint')}</div>
            <table className="tl-table">
              <thead>
                <tr>
                  <th>{t('teacher.summary.colClass')}</th>
                  {['0–59', '60–69', '70–79', '80–89', '90–100'].map((label) => (
                    <th key={label}>{label}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={`dist-${row.classId}`}>
                    <td><b>{row.className}</b></td>
                    {row.distribution.map((bucket) => (
                      <td key={bucket.label} className="mono">{bucket.count}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="card">
            <h2>{t('teacher.summary.accuracyTitle')}</h2>
            <div className="hint" style={{ marginBottom: 10 }}>{t('teacher.summary.accuracyHint')}</div>
            {rows.every((row) => row.itemAccuracy.length === 0) ? (
              <p className="muted">{t('teacher.summary.accuracyEmpty')}</p>
            ) : (
              <div style={{ maxHeight: 360, overflow: 'auto' }}>
                <table className="tl-table">
                  <thead>
                    <tr>
                      <th>{t('teacher.summary.colClass')}</th>
                      <th>{t('teacher.summary.accuracyNumber')}</th>
                      <th>{t('teacher.summary.accuracyAnswered')}</th>
                      <th>{t('teacher.summary.accuracyCorrect')}</th>
                      <th>{t('teacher.summary.accuracyRate')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.flatMap((row) => row.itemAccuracy.map((item) => (
                      <tr key={`acc-${row.classId}-${item.number}`}>
                        <td>{row.className}</td>
                        <td className="mono">{item.number}</td>
                        <td className="mono">{item.answered}</td>
                        <td className="mono">{item.correct}</td>
                        <td className="mono">{item.correctRate == null ? '—' : `${fmtRate(Number(item.correctRate))}`}</td>
                      </tr>
                    )))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="card">
            <h2>{t('teacher.summary.unsubmittedTitle')}</h2>
            {rows.every((row) => row.unsubmitted.length === 0) ? (
              <p className="muted">{t('teacher.summary.unsubmittedNone')}</p>
            ) : (
              <div className="wizard-list">
                {rows.map((row) => (
                  <div key={`miss-${row.classId}`} style={{ marginBottom: 10 }}>
                    <b>{row.className}</b>
                    <span className="muted small" style={{ marginLeft: 8 }}>
                      {t('teacher.summary.unsubmittedCount', { n: row.unsubmitted.length })}
                    </span>
                    <div className="small" style={{ marginTop: 4 }}>
                      {row.unsubmitted.map((stu) => stu.studentName).join('、')}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="card">
            <h2>{t('teacher.summary.exportTitle')}</h2>
            <div className="row">
              <a className="btn" href={`/api/offerings/${offeringId}/export/summary.md`}>
                {t('teacher.summary.exportMarkdown')}
              </a>
              <a className="btn" href={`/api/offerings/${offeringId}/export/summary.xlsx?lang=${lang}`}>
                {t('teacher.summary.exportXlsx')}
              </a>
              <a className="btn" href={`/api/offerings/${offeringId}/export/summary.docx?lang=${lang}`}>
                {t('teacher.summary.exportDocx')}
              </a>
              {offering?.publishScores ? (
                <>
                  <a className="btn" href={`/api/offerings/${offeringId}/export/feedback.zip`}>
                    {t('teacher.summary.exportZip')}
                  </a>
                  <a className="btn" href={`/api/offerings/${offeringId}/export/feedback.pdf?lang=${lang}`}>
                    {t('teacher.summary.exportPdf')}
                  </a>
                </>
              ) : (
                <>
                  <button type="button" className="btn" disabled title={t('teacher.summary.exportZipBlocked')}>
                    {t('teacher.summary.exportZip')}
                  </button>
                  <button type="button" className="btn" disabled title={t('teacher.summary.exportZipBlocked')}>
                    {t('teacher.summary.exportPdf')}
                  </button>
                </>
              )}
            </div>
            <div className="hint">{t('teacher.summary.exportHint')}</div>
          </div>

          <div className="hint">{t('teacher.summary.sameSource')}</div>
        </>
      )}
    </TeacherPage>
  )
}
