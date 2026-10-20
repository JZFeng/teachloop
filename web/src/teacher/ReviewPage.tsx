import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, fmtDateTime } from '../api/client'
import type { ReviewView } from '../api/types'
import { AuditDrawer } from '../components/AuditDrawer'
import { DataTable } from '../components/DataTable'
import type { Column } from '../components/DataTable'
import { useListState } from '../components/useListState'
import { ErrorState, Loading, PageHeader, StatCard } from '../components/States'

const zh = {
  title: '复核工作台',
  sub: 'AI 草稿 → 通过 / 弃用重批；改分与批量通过未接入',
  pending: '待复核',
  approved: '已通过',
  rejected: '已弃用重批',
  submission: '提交',
  status: '状态',
  createdAt: '入队时间',
  comment: '评语（可选）',
  approve: '通过',
  reject: '弃用重批',
  audit: '审计',
  empty: '队列为空',
  notConnected: '改分、批量通过、发布反馈需要 T101–T104 的后端接口，当前页面不伪造这些能力。',
  failed: '操作失败',
}
const en = {
  title: 'Review workbench',
  sub: 'AI draft → approve or discard; score editing and bulk approval are not connected',
  pending: 'To review',
  approved: 'Approved',
  rejected: 'Discarded',
  submission: 'Submission',
  status: 'Status',
  createdAt: 'Queued at',
  comment: 'Comment (optional)',
  approve: 'Approve',
  reject: 'Discard',
  audit: 'Audit',
  empty: 'Queue is empty',
  notConnected: 'Score editing, bulk approval and publishing need the T101–T104 backend endpoints; this page does not invent them.',
  failed: 'Action failed',
}

export default function ReviewPage() {
  const { i18n } = useTranslation()
  const L = i18n.language.startsWith('zh') ? zh : en
  const [rows, setRows] = useState<ReviewView[] | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [comments, setComments] = useState<Record<string, string>>({})
  const [auditFor, setAuditFor] = useState<string | null>(null)
  const { sort, page, pageSize, setSort, setPage } = useListState({ key: 'createdAt', dir: 'asc' })

  const load = useCallback(() => {
    setError(null)
    api.get<ReviewView[]>('/api/grading/queue')
      .then(setRows)
      .catch((e: Error) => setError(e))
  }, [])

  useEffect(load, [load])

  const decide = async (row: ReviewView, status: 'APPROVED' | 'REJECTED') => {
    setActionError(null)
    try {
      await api.post(`/api/submissions/${row.submissionId}/review`, {
        status,
        comment: comments[row.submissionId] || null,
      })
      load()
    } catch (e) {
      setActionError(e instanceof Error ? e.message : L.failed)
    }
  }

  const columns: Column<ReviewView>[] = [
    {
      key: 'submissionId', title: L.submission, value: (r) => r.submissionId, sortable: true,
      render: (r) => <span className="mono small" title={r.submissionId}>{r.submissionId.slice(0, 8)}</span>,
    },
    {
      key: 'createdAt', title: L.createdAt, value: (r) => r.createdAt, sortable: true,
      render: (r) => <span className="small">{fmtDateTime(r.createdAt)}</span>,
    },
    {
      key: 'status', title: L.status, value: (r) => r.status, sortable: true,
      render: (r) => <span className="tag amber">{L.pending}</span>,
    },
    {
      key: 'actions', title: L.approve, value: () => '', width: '320px',
      render: (r) => (
        <div className="row" style={{ gap: 6 }}>
          <input
            placeholder={L.comment}
            value={comments[r.submissionId] ?? ''}
            onChange={(e) => setComments((c) => ({ ...c, [r.submissionId]: e.target.value }))}
            style={{ minWidth: 120, flex: 1 }}
          />
          <button className="btn sm pri" onClick={() => void decide(r, 'APPROVED')}>{L.approve}</button>
          <button className="btn sm danger" onClick={() => void decide(r, 'REJECTED')}>{L.reject}</button>
          <button className="btn sm" onClick={() => setAuditFor(r.submissionId)}>{L.audit}</button>
        </div>
      ),
    },
  ]

  const pending = rows?.length ?? 0

  return (
    <>
      <PageHeader title={L.title} sub={L.sub} />
      <div className="stats">
        <StatCard n={pending} label={L.pending} />
      </div>
      {error && <ErrorState error={error} onRetry={load} />}
      {actionError && <p className="inline-note">{actionError}</p>}
      {!rows && !error && <Loading />}
      {rows && (
        <DataTable
          rows={rows}
          columns={columns}
          rowKey={(r) => r.id}
          sort={sort}
          page={page}
          pageSize={pageSize}
          onSortChange={setSort}
          onPageChange={setPage}
          empty={L.empty}
        />
      )}
      <p className="hint">{L.notConnected}</p>
      <AuditDrawer
        open={auditFor !== null}
        onClose={() => setAuditFor(null)}
        targetType="submission"
        targetId={auditFor ?? ''}
        title={L.audit}
      />
    </>
  )
}
