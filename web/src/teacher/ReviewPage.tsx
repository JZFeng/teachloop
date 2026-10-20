import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, fmtDateTime } from '../api/client'
import { errorMessage } from './common'
import type { BulkApproveResult, ReviewDetail, ReviewView } from '../api/types'
import { AuditDrawer } from '../components/AuditDrawer'
import { DataTable } from '../components/DataTable'
import type { Column } from '../components/DataTable'
import { Empty, ErrorState, Loading, PageHeader, StatCard } from '../components/States'

const zh = {
  title: '复核工作台', sub: '逐份人工判断：看正文与 AI 草稿，通过 / 改后通过 / 弃用重批，再发布',
  pending: '待复核', bulk: '批量通过当前队列', batchResult: '已通过 {{approved}} 份，跳过 {{skipped}} 份（共 {{total}} 份）',
  open: '打开', queue: '待复核队列', emptyQueue: '队列为空',
  content: '学生正文', objective: '客观分', subjective: '主观分', aiDraft: 'AI 主观草稿',
  items: '题项与作答', answerKey: '标准答案', answer: '作答', points: '分',
  edit: '编辑复核', comment: '评语', strengths: '优点', suggestions: '建议', save: '保存修改',
  approve: '原样通过', reject: '弃用重批', rejectReason: '驳回原因（可选）', publish: '发布反馈', withdraw: '撤回',
  status: '状态', published: '已发布', unpublished: '未发布', noDetail: '从左侧选择一份提交',
  audit: '审计', editSaved: '已保存（状态：改后通过）', error: '操作失败', rubric: '评分标准',
  notConnectedScore: '当前端口暂不返回该分数',
}

export default function ReviewPage() {
  const { t, i18n } = useTranslation('translation')
  const L = i18n.language.startsWith('zh') ? zh : {
    ...zh,
    title: 'Review workbench', sub: 'Judge one submission at a time: read, approve / edit / discard, then publish',
    pending: 'To review', bulk: 'Approve current queue', batchResult: '{{approved}} approved, {{skipped}} skipped ({{total}} total)',
    open: 'Open', queue: 'Review queue', emptyQueue: 'Queue is empty',
    content: 'Student text', objective: 'Objective', subjective: 'Subjective', aiDraft: 'AI subjective draft',
    items: 'Items and answers', answerKey: 'Answer key', answer: 'Answer', points: 'pts',
    edit: 'Edit review', comment: 'Comment', strengths: 'Strengths', suggestions: 'Suggestions', save: 'Save edits',
    approve: 'Approve as is', reject: 'Discard & regrade', rejectReason: 'Reject reason (optional)', publish: 'Publish feedback', withdraw: 'Withdraw',
    status: 'Status', published: 'Published', unpublished: 'Not published', noDetail: 'Pick a submission from the queue',
    audit: 'Audit', editSaved: 'Saved (status: edited)', error: 'Action failed', rubric: 'Rubric',
    notConnectedScore: 'This score is not exposed by the current port',
  }

  const [queue, setQueue] = useState<ReviewView[] | null>(null)
  const [queueError, setQueueError] = useState<Error | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [detail, setDetail] = useState<ReviewDetail | null>(null)
  const [detailError, setDetailError] = useState<Error | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [batch, setBatch] = useState<BulkApproveResult | null>(null)
  const [auditFor, setAuditFor] = useState<string | null>(null)
  const [form, setForm] = useState({ subjectivePoints: '', comment: '', strengths: '', suggestions: '', reason: '' })

  const loadQueue = useCallback(() => {
    setQueueError(null)
    api.get<ReviewView[]>('/api/grading/queue')
      .then((rows) => {
        setQueue(rows)
        if (!selected && rows.length > 0) setSelected(rows[0].submissionId)
      })
      .catch((e: Error) => setQueueError(e))
  }, [selected])

  useEffect(() => { loadQueue() }, [loadQueue])

  const loadDetail = useCallback(() => {
    if (!selected) { setDetail(null); return }
    setDetailError(null)
    setActionError(null)
    api.get<ReviewDetail>(`/api/submissions/${selected}/review`)
      .then((d) => {
        setDetail(d)
        setForm({
          subjectivePoints: d.aiSubjectiveDraft == null ? '' : String(d.aiSubjectiveDraft),
          comment: d.review.comment ?? '',
          strengths: d.review.strengths ?? '',
          suggestions: d.review.suggestions ?? '',
          reason: '',
        })
      })
      .catch((e: Error) => setDetailError(e))
  }, [selected])

  useEffect(() => { loadDetail() }, [loadDetail])

  const act = async (fn: () => Promise<unknown>, message?: string) => {
    setBusy(true)
    setActionError(null)
    try {
      await fn()
      if (message) setActionError(message)
      loadQueue()
      loadDetail()
    } catch (e) {
      setActionError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const saveEdits = () => act(() => api.put(`/api/submissions/${selected}/review`, {
    subjectivePoints: form.subjectivePoints === '' ? null : Number(form.subjectivePoints),
    comment: form.comment || null,
    strengths: form.strengths || null,
    suggestions: form.suggestions || null,
  }), L.editSaved)

  const decide = (status: 'APPROVED' | 'REJECTED') => act(() => api.post(`/api/submissions/${selected}/review`, {
    status, comment: status === 'REJECTED' ? (form.reason || form.comment || null) : (form.comment || null),
    reason: status === 'REJECTED' ? (form.reason || null) : null,
  }))

  const publish = () => act(() => api.post(`/api/submissions/${selected}/review/publish`))
  const withdraw = () => act(() => api.post(`/api/submissions/${selected}/review/withdraw`))

  const bulkApprove = async () => {
    if (!queue || queue.length === 0) return
    setBusy(true)
    setActionError(null)
    try {
      setBatch(await api.post<BulkApproveResult>('/api/grading/bulk-approve', {
        submissionIds: queue.map((r) => r.submissionId),
      }))
      loadQueue()
    } catch (e) {
      setActionError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const columns: Column<ReviewView>[] = [
    { key: 'submissionId', title: 'Submission', value: (r) => r.submissionId, sortable: true, render: (r) => <span className="mono small">{r.submissionId.slice(0, 8)}</span> },
    { key: 'status', title: L.status, value: (r) => r.status, sortable: true, render: (r) => <span className="tag amber">{r.status}</span> },
    { key: 'createdAt', title: 'Queued at', value: (r) => r.createdAt, sortable: true, render: (r) => <span className="small">{fmtDateTime(r.createdAt)}</span> },
    { key: 'actions', title: L.open, value: () => '', render: (r) => <button className="btn sm" onClick={() => setSelected(r.submissionId)}>{L.open}</button> },
  ]

  return (
    <>
      <PageHeader title={L.title} sub={L.sub} />
      <div className="stats">
        <StatCard n={queue?.length ?? 0} label={L.pending} />
      </div>
      <div className="row" style={{ marginBottom: 10 }}>
        <button className="btn" disabled={busy || !queue?.length} onClick={() => void bulkApprove()}>{L.bulk}</button>
        {batch && <span className="small muted">{L.batchResult.replace('{{approved}}', String(batch.approved)).replace('{{skipped}}', String(batch.skipped)).replace('{{total}}', String(batch.total))}</span>}
      </div>
      {queueError && <ErrorState error={queueError} onRetry={loadQueue} />}
      {!queue && !queueError && <Loading />}
      {queue && queue.length === 0 && <Empty>{L.emptyQueue}</Empty>}
      {queue && queue.length > 0 && (
        <DataTable rows={queue} columns={columns} rowKey={(r) => r.id} pageSize={10} empty={L.emptyQueue} />
      )}

      {!selected ? <p className="muted">{L.noDetail}</p> : (
        <>
          {detailError && <ErrorState error={detailError} onRetry={loadDetail} />}
          {!detail && !detailError && <Loading />}
          {detail && (
            <div className="card">
              <div className="row" style={{ justifyContent: 'space-between' }}>
                <h2 style={{ margin: 0 }}>{L.edit} <span className="mono small">{detail.submissionId.slice(0, 8)}</span></h2>
                <span className="tag gray">{fmtDateTime(detail.review.createdAt)}</span>
              </div>
              {actionError && <div className="inline-note">{actionError}</div>}

              <h3>{L.content}</h3>
              <div className="card" style={{ background: 'var(--panel-2)' }}>
                <div style={{ whiteSpace: 'pre-wrap' }}>{detail.contentText || '—'}</div>
                <div className="row" style={{ gap: 24, marginTop: 10 }}>
                  <div><span className="muted small">{L.objective}</span> <b className="mono">{detail.objectivePoints ?? '—'}</b></div>
                  <div><span className="muted small">{L.subjective}</span> <b className="mono">{detail.subjectivePoints ?? '—'}</b></div>
                  <div><span className="muted small">{L.aiDraft}</span> <b className="mono">{detail.aiSubjectiveDraft ?? '—'}</b></div>
                </div>
                {(detail.objectivePoints == null || detail.subjectivePoints == null) && <div className="hint">{L.notConnectedScore}</div>}
              </div>

              <h3>{L.items}</h3>
              <table>
                <thead><tr><th>#</th><th>{L.answer}</th><th>{L.answerKey}</th><th>{L.points}</th></tr></thead>
                <tbody>
                  {detail.items.map((item) => {
                    const answer = detail.answers.find((a) => a.number === item.number)?.answer
                    return (
                      <tr key={item.number}>
                        <td className="mono">{item.number}</td>
                        <td className="small">{answer ?? '—'}</td>
                        <td className="small mono">{item.answerKey ?? '—'}</td>
                        <td className="mono">{item.points}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
              {detail.rubricMarkdown && <details style={{ marginTop: 10 }}><summary>{L.rubric}</summary><pre className="small" style={{ whiteSpace: 'pre-wrap' }}>{detail.rubricMarkdown}</pre></details>}

              <h3>{L.edit}</h3>
              <div className="row" style={{ alignItems: 'flex-start' }}>
                <label className="f" style={{ width: 120 }}><b>{L.subjective}</b>
                  <input type="number" step="0.5" min={0} value={form.subjectivePoints}
                    onChange={(e) => setForm({ ...form, subjectivePoints: e.target.value })} />
                </label>
                <label className="f" style={{ flex: 1, minWidth: 220 }}><b>{L.comment}</b>
                  <textarea rows={3} value={form.comment} onChange={(e) => setForm({ ...form, comment: e.target.value })} />
                </label>
                <label className="f" style={{ flex: 1, minWidth: 220 }}><b>{L.strengths}</b>
                  <textarea rows={3} value={form.strengths} onChange={(e) => setForm({ ...form, strengths: e.target.value })} />
                </label>
                <label className="f" style={{ flex: 1, minWidth: 220 }}><b>{L.suggestions}</b>
                  <textarea rows={3} value={form.suggestions} onChange={(e) => setForm({ ...form, suggestions: e.target.value })} />
                </label>
              </div>

              <div className="row" style={{ gap: 8, marginTop: 8 }}>
                <button className="btn" disabled={busy || detail.review.published} onClick={() => void saveEdits()}>{L.save}</button>
                <button className="btn pri" disabled={busy || detail.review.published} onClick={() => void decide('APPROVED')}>{L.approve}</button>
                <button className="btn danger" disabled={busy || detail.review.published} onClick={() => void decide('REJECTED')}>{L.reject}</button>
                <input placeholder={L.rejectReason} value={form.reason}
                  onChange={(e) => setForm({ ...form, reason: e.target.value })}
                  disabled={detail.review.published} style={{ flex: 1, minWidth: 180 }} />
                <button className="btn" disabled={busy || detail.review.published || !(detail.review.status === 'APPROVED' || detail.review.status === 'EDITED')}
                  onClick={() => void publish()}>{L.publish}</button>
                <button className="btn" disabled={busy || !detail.review.published} onClick={() => void withdraw()}>{L.withdraw}</button>
                <button className="btn" onClick={() => setAuditFor(detail.submissionId)}>{L.audit}</button>
              </div>
              <p className="small muted" style={{ marginTop: 8 }}>
                {detail.review.published ? L.published : L.unpublished}
                {detail.review.publishedAt ? ` · ${fmtDateTime(detail.review.publishedAt)}` : ''}
              </p>
            </div>
          )}
        </>
      )}

      <AuditDrawer open={auditFor !== null} onClose={() => setAuditFor(null)}
        targetType="submission" targetId={auditFor ?? ''} title={L.audit} />
    </>
  )
}
